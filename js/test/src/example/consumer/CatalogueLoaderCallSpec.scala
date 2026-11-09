package example.consumer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.scalajs.js.URIUtils

import zio.test.*

import zio.ZIO

import dev.taihei.tsuji.*

// An application outside the library's package sees one load: the call written for 6.0.x still
// compiles unchanged and takes the defaulted timeout, and the new argument is named.
object CatalogueLoaderCallSpec extends ZIOSpecDefault:

  private val File = """{"format":1,"language":"de","messages":{}}"""
  private val Url  = "data:application/json," + URIUtils.encodeURIComponent(File)

  def spec = suite("CatalogueLoader.load from an application")(
    test("the 6.0.x call and the call with a timeout both load") {
      for
        plain  <- ZIO.fromFuture(ec =>
          given ExecutionContext = ec
          CatalogueLoader.load(Catalogues(Seq.empty), Language("de"), Url)
        )
        timely <- ZIO.fromFuture(ec =>
          given ExecutionContext = ec
          CatalogueLoader.load(Catalogues(Seq.empty), Language("de"), Url, timeout = Some(5.seconds))
        )
      yield assertTrue(plain.isEmpty, timely.isEmpty)
    }
  )

end CatalogueLoaderCallSpec
