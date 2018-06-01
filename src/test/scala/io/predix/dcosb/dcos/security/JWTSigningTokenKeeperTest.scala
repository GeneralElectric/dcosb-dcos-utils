package io.predix.dcosb.dcos.security

import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.testkit.{CallingThreadDispatcher, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpecLike, Matchers, OneInstancePerTest}
import akka.pattern.ask
import akka.util.Timeout
import io.predix.dcosb.config.model.DCOSClusterConnectionParameters
import io.predix.dcosb.util.actor.ConfiguredActor
import pdi.jwt.{Jwt, JwtAlgorithm}
import better.files._
import java.io.{File => JFile}
import java.security.{KeyFactory, PrivateKey}

import org.joda.time.DateTime
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Failure, Success}
import com.github.nscala_time.time.Imports._
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.util.ActorSuite

class JWTSigningTokenKeeperTest extends ActorSuite {
  implicit val timeout: Timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
  //implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)


  "A JWTSigningTokenKeeper" - {

    " when responding to a Configuration message" - {

      val httpClient = (_: HttpRequest, _:String) => { Future.failed[(HttpResponse, String)](new NotImplementedException) }

      " should fail to respond with Configured when neither principal nor privateKey is present in Configuration" in {

        val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
        val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))
        val responseFuture = (tokenKeeper ? TokenKeeper.Configuration(DCOSCommon.Connection(None, None, "no principal & no privatekey", 8080), httpClient))

        implicit val dispatcher = system.dispatchers.lookup(CallingThreadDispatcher.Id)

        Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) shouldEqual Failure(TokenKeeper.InvalidCredentials(None, false))

      }

      "should fail to respond with Configured when a principal is present without privateKey in Configuration" in {

        val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))
        val responseFuture = (tokenKeeper ? TokenKeeper.Configuration(DCOSCommon.Connection(Some("principal"), None, "principal & no privatekey", 8080), httpClient))

        Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) shouldEqual Failure(TokenKeeper.InvalidCredentials(Some("principal"), false))

      }

      "should fail to respond with Configured when a privateKey is present without a principal in Configuration" in {

        val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))
        val responseFuture = (tokenKeeper ? TokenKeeper.Configuration(DCOSCommon.Connection( None, Some("secret".getBytes()), "no principal & privateky", 8080), httpClient))

        Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) shouldEqual Failure(TokenKeeper.InvalidCredentials(None, true))

      }

      "should respond with Configured when credentials are present in Configuration" in {

        val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))
        val responseFuture = (tokenKeeper ? TokenKeeper.Configuration(DCOSCommon.Connection(Some("principal"), Some("".getBytes()), "principal & privatekey", 8080), httpClient))

        Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) shouldEqual Success(ConfiguredActor.Configured())

      }

    }

    " with no Token stored" - {


      " Configured with valid credentials" - {

        trait ValidCredentials {
          // return a token for a JWT signed http POST to /acs/api/v1/auth/login
          val privateKeyBytes = File.resource("jwtRS256.key.pkcs8").loadBytes
          val privateKey : PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

          val loginToken = Jwt.encode("""{"uid":"principal"}""", privateKey, JwtAlgorithm.RS256)
          val loginEntity = HttpEntity(ContentTypes.`application/json`, s"""{"uid":"principal","token":"${loginToken}"}""")

          val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
          httpClient expects(HttpRequest(method = HttpMethods.POST, uri = "/acs/api/v1/auth/login", entity = loginEntity), *) returning Future.successful((HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"token":"reallyvalidtoken!"}""")), ""))

          val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))

          tokenKeeper ! TokenKeeper.Configuration(DCOSCommon.Connection(Some("principal"), Some(privateKeyBytes), "localhost", 8080), httpClient)

        }



      " responds with a Token to a GetOrRefreshToken message" in new ValidCredentials {

          val responseFuture = (tokenKeeper ? TokenKeeper.GetOrRefreshToken())

          Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) match {
            case Success(TokenKeeper.Token(_: DateTime, token: String)) => token shouldEqual "reallyvalidtoken!"
            case r => fail(s"Unexpected response from TokenKeeper: ${r}")
          }


        }



      }

    }

    "with a Token stored" - {

      trait EmptyHttpClientMock {

        val httpClient = mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
        val tokenKeeper = system.actorOf(Props(classOf[TokenKeeper.JWTSigningTokenKeeper]).withDispatcher(CallingThreadDispatcher.Id))
        tokenKeeper ! TokenKeeper.Configuration(DCOSCommon.Connection(Some("principal"), Some("secret".getBytes), "localhost", 8080), httpClient)

      }

      " responds with the stored Token to a GetOrRefreshToken message" in new EmptyHttpClientMock {

        val token = TokenKeeper.Token(DateTime.now + 2.days, "foo")
        Await.result((tokenKeeper ? TokenKeeper.SetToken(token)), FiniteDuration(1, TimeUnit.SECONDS))

        val responseFuture = (tokenKeeper ? TokenKeeper.GetOrRefreshToken())
        Await.result(responseFuture, FiniteDuration(1, TimeUnit.SECONDS)) match {
          case Success(t: TokenKeeper.Token) => t shouldEqual token
          case r => fail(s"Unexpected response from TokenKeeper: ${r}")
        }

      }


    }

  }


}
