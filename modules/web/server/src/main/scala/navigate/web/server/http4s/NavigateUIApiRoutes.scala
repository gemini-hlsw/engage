// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package navigate.web.server.http4s

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.Sync
import cats.syntax.all.*
import fs2.Pipe
import fs2.Stream
import fs2.concurrent.Topic
import lucuma.core.enums.Site
import navigate.model.ClientId
import navigate.model.NavigateEvent
import navigate.model.NavigateEvent.ConnectionOpenEvent
import navigate.model.NavigateEvent.ForClient
import navigate.model.NavigateEvent.NullEvent
import navigate.model.NavigateEvent.given
import navigate.model.boopickle.*
import navigate.model.config.*
import navigate.model.given
import navigate.model.security.UserLoginRequest
import navigate.server.NavigateEngine
import navigate.web.server.OcsBuildInfo
import navigate.web.server.http4s.encoder.*
import navigate.web.server.security.AuthenticationService
import navigate.web.server.security.AuthenticationService.AuthResult
import navigate.web.server.security.Http4sAuthentication
import navigate.web.server.security.TokenRefresher
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.*
import org.http4s.headers.Origin
import org.http4s.headers.`User-Agent`
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.server.middleware.CORS
import org.http4s.server.middleware.GZip
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Binary
import org.http4s.websocket.WebSocketFrame.Close
import org.http4s.websocket.WebSocketFrame.Ping
import org.http4s.websocket.WebSocketFrame.Pong
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration.*
import scala.math.*

/**
 * Rest Endpoints under the /api route
 */
class NavigateUIApiRoutes[F[_]: Async](
  site:         Site,
  mode:         Mode,
  auth:         AuthenticationService[F],
  clientsDb:    ClientsSetDb[F],
  engineOutput: Topic[F, NavigateEvent]
)(using
  L:            Logger[F]
) extends Http4sDsl[F] {

  private val unauthorized =
    Unauthorized(`WWW-Authenticate`(NonEmptyList.of(Challenge("jwt", "navigate"))))

  // Handles authentication
  private val httpAuthentication = new Http4sAuthentication(auth)

  val pingInterval: FiniteDuration = 10.second

  /**
   * Creates a process that sends a ping every second to keep the connection alive
   */
  private def pingStream: Stream[F, Ping] =
    Stream.fixedRate[F](pingInterval).flatMap(_ => Stream.emit(Ping()))

  val publicService: HttpRoutes[F] = GZip {
    HttpRoutes.of {

      case req @ POST -> Root / "navigate" / "login" =>
        req.decode[UserLoginRequest] { (u: UserLoginRequest) =>
          // Try to authenticate
          auth.authenticateUser(u.username, u.password).flatMap {
            case Right(user) =>
              // Log who logged in
              // Note that the call to read a remoteAddr may do a DNS lookup
              req.remoteHost.flatMap { x =>
                L.info(s"${user.displayName} logged in from ${x.getOrElse("Unknown")}")
              } *>
                // if successful set a cookie
                httpAuthentication.loginCookie(user) >>= { cookie =>
                Ok(user).map(_.addCookie(cookie))
              }
            case Left(_)     =>
              unauthorized
          }
        }

      case POST -> Root / "navigate" / "logout" =>
        // Clean the auth cookie
        val cookie = ResponseCookie(auth.config.cookieName,
                                    "",
                                    path = "/".some,
                                    secure = auth.config.useSSL,
                                    maxAge = Some(-1),
                                    httpOnly = true
        )
        Ok("").map(_.removeCookie(cookie))

    }
  }

  def protectedServices(wsBuilder: WebSocketBuilder2[F]): AuthedRoutes[AuthResult, F] =
    AuthedRoutes.of {
      // Route used for testing only
      case GET -> Root / "log" / IntVar(count) as _ if mode === Mode.Development =>
        (L.info("info") *>
          L.warn("warn") *>
          L.error("error")).replicateA(min(1000, max(0, count))) *> Ok("")

      case auth @ POST -> Root / "navigate" / "site" as user =>
        val userName = user.fold(_ => "Anonymous", _.displayName)
        // Login start
        auth.req.remoteHost.flatMap { x =>
          L.info(s"$userName connected from ${x.getOrElse("Unknown")}")
        } *>
          Ok(s"$site")

      case ws @ GET -> Root / "navigate" / "events" as user =>
        // If the user didn't login, anonymize
        val anonymizeF: NavigateEvent => NavigateEvent = user.fold(_ => anonymize, _ => identity)

        def initialEvent(clientId: ClientId): Stream[F, WebSocketFrame] =
          Stream.emit(toFrame(ConnectionOpenEvent(user.toOption, clientId, OcsBuildInfo.version)))

        def engineEvents(clientId: ClientId): Stream[F, WebSocketFrame] =
          engineOutput
            .subscribe(100)
            .map(anonymizeF)
            .filter(filterOutNull)
            .filter(filterOutOnClientId(clientId))
            .map(toFrame)
        val clientSocket                                                = (ws.req.remoteAddr, ws.req.remotePort).mapN((a, p) => s"$a:$p").orEmpty
        val userAgent                                                   = ws.req.headers.get[`User-Agent`]

        // We don't care about messages sent over ws by clients but we want to monitor
        // control frames and track that pings arrive from clients
        def clientEventsSink(clientId: ClientId): Pipe[F, WebSocketFrame, Unit] =
          _.flatTap {
            case Close(_) =>
              Stream.eval(
                clientsDb.removeClient(clientId) *> L.debug(s"Closed client $clientSocket")
              )
            case Pong(_)  => Stream.eval(L.trace(s"Pong from $clientSocket"))
            case _        => Stream.empty
          }.filter {
            case Pong(_) => true
            case _       => false
          }.void
            .through(
              NavigateEngine.failIfNoEmitsWithin(5 * pingInterval, s"Lost ping on $clientSocket")
            )

        // Create a client specific websocket
        for {
          clientId <- Sync[F].delay(ClientId(UUID.randomUUID()))
          _        <- clientsDb.newClient(clientId, clientSocket, userAgent)
          _        <- L.info(s"New client $clientSocket => ${clientId.self}")
          initial   = initialEvent(clientId)
          streams   = Stream(pingStream, engineEvents(clientId)).parJoinUnbounded
                        .onFinalize[F](clientsDb.removeClient(clientId))
          ws       <- wsBuilder
                        .withFilterPingPongs(filterPingPongs = false)
                        .build(initial ++ streams, clientEventsSink(clientId))
        } yield ws

    }

  val corsOriginSvc = CORS.policy
    // FIXME limits the origin for production
    .withAllowOriginAll
    .withAllowMethodsIn(Set(Method.GET, Method.POST))

  def service(wsBuilder: WebSocketBuilder2[F]): HttpRoutes[F] =
    corsOriginSvc(
      publicService <+> TokenRefresher(
        GZip(httpAuthentication.optAuth(protectedServices(wsBuilder))),
        httpAuthentication
      )
    )

  // Event to WebSocket frame
  private def toFrame(e: NavigateEvent) =
    Binary(ByteVector(trimmedArray(e)))

  // Stream navigate events to clients and a ping
  private def anonymize(e: NavigateEvent) = e

  // Filter out NullEvents from the engine
  private def filterOutNull =
    (e: NavigateEvent) =>
      e match {
        case NullEvent => false
        case _         => true
      }

  // Messages with a clientId are only sent to the matching client
  private def filterOutOnClientId(clientId: ClientId) =
    (e: NavigateEvent) =>
      e match {
        case e: ForClient if e.clientId =!= clientId => false
        case _                                       => true
      }

}
