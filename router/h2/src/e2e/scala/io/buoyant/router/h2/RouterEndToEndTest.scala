package io.buoyant.router
package h2

import com.twitter.finagle.{Dtab, Failure, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import com.twitter.logging.Level
import com.twitter.util.{Future, Promise, Throw}
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class RouterEndToEndTest
  extends FunSuite
  with ClientServerHelpers {

  test("router with prior knowledge") {
    val cat = Downstream.const("cat", "meow")
    val dog = Downstream.const("dog", "woof")
    val dtab = Dtab.read(s"""
        /p/cat => /$$/inet/127.1/${cat.port} ;
        /p/dog => /$$/inet/127.1/${dog.port} ;

        /h2/felix    => /p/cat ;
        /h2/clifford => /p/dog ;
      """)
    val identifierParam = H2.Identifier { _ =>
      req => {
        val dst = Dst.Path(Path.Utf8("h2", req.authority), dtab)
        Future.value(new RoutingFactory.IdentifiedRequest(dst, req))
      }
    }
    val router = H2.serve(new InetSocketAddress(0), H2.router
      .configured(identifierParam)
      .factory())
    val client = upstream(router)
    try {
      client.get("felix")(_ == Some("meow"))
      client.get("clifford", "/the/big/red/dog")(_ == Some("woof"))
    } finally {
      setLogLevel(Level.OFF)
      await(client.close())
      await(cat.server.close())
      await(dog.server.close())
      await(router.close())
    }
  }

  test("router with prior knowledge: resets downstream on upstream cancelation") {
    val dogReqP, dogRspP = new Promise[Stream]
    @volatile var serverInterrupted: Option[Throwable] = None
    dogRspP.setInterruptHandler { case e => serverInterrupted = Some(e) }
    val dog = Downstream.service("dog") { req =>
      dogReqP.setValue(req.stream)
      dogRspP.map(Response(Status.Ok, _))
    }

    val dtab = Dtab.read(s"""
        /p => /$$/inet/127.1 ;
        /h2/clifford => /p/${dog.port} ;
      """)
    val identifierParam = H2.Identifier { _ =>
      req => {
        val dst = Dst.Path(Path.Utf8("h2", req.authority), dtab)
        Future.value(new RoutingFactory.IdentifiedRequest(dst, req))
      }
    }
    val router = H2.serve(new InetSocketAddress(0), H2.router
      .configured(identifierParam)
      .factory())
    val client = upstream(router)
    try {
      val clientLocalStream, serverLocalStream = Stream()
      val req = Request("http", Method.Get, "clifford", "/path", clientLocalStream)
      val rspF = client(req)
      val reqStream = await(dogReqP)
      val readF = reqStream.read()
      assert(!rspF.isDefined)
      assert(!readF.isDefined)

      rspF.raise(Failure("failz").flagged(Failure.Interrupted))
      assert(await(rspF.liftToTry) == Throw(Reset.Cancel))
      // assert(await(readF.liftToTry) == Throw(Reset.Cancel))
      eventually(assert(serverInterrupted == Some(Reset.Cancel)))

    } finally {
      setLogLevel(Level.OFF)
      try await(client.close().before(dog.server.close()).before(router.close()))
      catch { case e: Throwable => log.error(e, "closing") }
    }
  }

  test("router with prior knowledge: resets upstream on downstream cancelation") {
    val dogReqP, dogRspP = new Promise[Stream]
    val dog = Downstream.service("dog") { req =>
      dogReqP.setValue(req.stream)
      dogRspP.map(Response(Status.Ok, _))
    }

    val dtab = Dtab.read(s"""
        /p => /$$/inet/127.1 ;
        /h2/clifford => /p/${dog.port} ;
      """)
    val identifierParam = H2.Identifier { _ =>
      req => {
        val dst = Dst.Path(Path.Utf8("h2", req.authority), dtab)
        Future.value(new RoutingFactory.IdentifiedRequest(dst, req))
      }
    }
    val router = H2.serve(new InetSocketAddress(0), H2.router
      .configured(identifierParam)
      .factory())
    val client = upstream(router)
    try {
      val clientLocalStream, serverLocalStream = Stream()
      val req = Request("http", Method.Get, "clifford", "/path", clientLocalStream)
      val rspF = client(req)
      val _ = await(dogReqP)
      assert(!rspF.isDefined)

      dogRspP.setException(Failure("failz").flagged(Failure.Rejected))
      assert(await(rspF.liftToTry) == Throw(Reset.Refused))

    } finally {
      try await(client.close().before(dog.server.close()).before(router.close()))
      catch { case e: Throwable => log.error(e, "closing") }
      finally setLogLevel(Level.OFF)
    }
  }
}