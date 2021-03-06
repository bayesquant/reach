package controllers

import javax.inject._
import org.clulab.odin.{Attachment, EventMention, Mention, RelationMention, TextBoundMention}
import org.clulab.processors.{Document, Sentence}
import org.clulab.reach.ReachSystem
import org.clulab.reach.mentions.{BioEventMention, BioTextBoundMention}
import org.clulab.sequences.LexiconNER
import play.api._
import play.api.mvc._
import play.api.libs.json._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  // Initialize the ReachSystem
  // -------------------------------------------------
  println("[ReachSystem] Initializing the ReachSystem ...")
  val ieSystem = new ReachSystem()
  var proc = ieSystem.procAnnotator
  println("[ReachSystem] Completed Initialization ...")
  // -------------------------------------------------

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }
//
//  def buildInfo = Action {
//    Ok(jsonBuildInfo)
//  }

  // Entry method
  def parseSentence(text: String) = Action {
    val (doc, events, mentions) = processPlaySentence(ieSystem, text)
    println(s"Sentence returned from processPlaySentence : ${doc.sentences.head.getSentenceText}")
    val json = mkJson(text, doc, events, mentions) // we only handle a single sentence
    Ok(json)
  }

  // Method where reach happens!
  def processPlaySentence(
    ieSystem: ReachSystem,
    text: String): (Document, Seq[BioEventMention], Seq[BioTextBoundMention]) = {

    def mentionOrder(m: Mention): Int = 10000 * m.sentence + m.start

    // preprocessing
    println(s"Processing sentence : ${text}" )
    val doc = ieSystem.mkDoc(text, "WebApp")

    // Debug
    println(s"DOC : ${doc}")
    // extract mentions from annotated document
    val entities = ieSystem.extractEntitiesFrom(doc)
    val events = ieSystem.extractEventsFrom(doc, entities)

    val mentions = entities ++ events

    println(s"Done extracting the mentions ... ")
    println(s"They are : ${mentions.map(m => m.text).mkString(",\t")}")


    println("DONE .... ")
    //    println(s"Grounded Adjectives : ${groundedAdjectives.size}")
    // return the sentence and all the mentions extracted ... TODO: fix it to process all the sentences in the doc
    (doc, events collect { case m:BioEventMention => m }, entities  map (_.asInstanceOf[BioTextBoundMention]))
  }


//  val jsonBuildInfo: JsValue = Json.obj(
//    "name" -> BuildInfo.name,
//    "version" -> BuildInfo.version,
//    "scalaVersion" -> BuildInfo.scalaVersion,
//    "sbtVersion" -> BuildInfo.sbtVersion,
//    "libraryDependencies" -> BuildInfo.libraryDependencies,
//    "scalacOptions" -> BuildInfo.scalacOptions,
//    "gitCurrentBranch" -> BuildInfo.gitCurrentBranch,
//    "gitHeadCommit" -> BuildInfo.gitHeadCommit,
//    "gitHeadCommitDate" -> BuildInfo.gitHeadCommitDate,
//    "gitUncommittedChanges" -> BuildInfo.gitUncommittedChanges,
//    "builtAtString" -> BuildInfo.builtAtString,
//    "builtAtMillis" -> BuildInfo.builtAtMillis
//  )

  protected def mkParseObj(sentence: Sentence, sb: StringBuilder): Unit = {
    def getTdAt(option: Option[Array[String]], n: Int): String = {
      val text = if (option.isEmpty) ""
      else option.get(n)

      "<td>" + xml.Utility.escape(text) + "</td>"
    }

    sentence.words.indices.foreach { i =>
      sb
        .append("<tr>")
        .append("<td>" + xml.Utility.escape(sentence.words(i)) + "</td>")
        .append(getTdAt(sentence.tags, i))
        .append(getTdAt(sentence.lemmas, i))
        .append(getTdAt(sentence.entities, i))
        .append(getTdAt(sentence.norms, i))
        .append(getTdAt(sentence.chunks, i))
        .append("</tr>")
    }
  }

  protected def mkParseObj(doc: Document): String = {
      val header =
      """
        |  <tr>
        |    <th>Word</th>
        |    <th>Tag</th>
        |    <th>Lemma</th>
        |    <th>Entity</th>
        |    <th>Norm</th>
        |    <th>Chunk</th>
        |  </tr>
      """.stripMargin
      val sb = new StringBuilder(header)

      doc.sentences.foreach(mkParseObj(_, sb))
      sb.toString
  }

  def mkJson(text: String, doc: Document, events:Seq[BioEventMention], entities:Seq[BioTextBoundMention]): JsValue = {
    println("Found mentions (in mkJson):")

    val sent = doc.sentences.head
    val syntaxJsonObj = Json.obj(
        "text" -> text,
        "entities" -> mkJsonFromTokens(doc),
        "relations" -> mkJsonFromDependencies(doc)
      )
    val reachJsonObj = mkJsonForReach(text, sent, events ++ entities)
    val parseObj = mkParseObj(doc)

    Json.obj(
      "syntax" -> syntaxJsonObj,
      "reachMentions" -> reachJsonObj,
      "parse" -> parseObj
    )
  }

  def mkJsonForReach(sentenceText: String, sent: Sentence, mentions: Seq[Mention]): Json.JsValueWrapper = {
    val topLevelTBM = mentions.flatMap {
      case m: TextBoundMention => Some(m)
      case _ => None
    }
    // collect event mentions for display
    val events = mentions.flatMap {
      case m: EventMention => Some(m)
      case _ => None
    }
    // collect triggers for event mentions
    val triggers = events.flatMap { e =>
      val argTriggers = for {
        a <- e.arguments.values
        if a.isInstanceOf[EventMention]
      } yield a.asInstanceOf[EventMention].trigger
      e.trigger +: argTriggers.toSeq
    }
    // collect event arguments as text bound mentions
    val entities = for {
      e <- events
      a <- e.arguments.values.flatten
    } yield a match {
      case m: TextBoundMention => m
      case m: RelationMention => new TextBoundMention(m.labels, m.tokenInterval, m.sentence, m.document, m.keep, m.foundBy)
      case m: EventMention => m.trigger
    }
    // generate id for each textbound mention
    val tbMentionToId = (entities ++ triggers ++ topLevelTBM)
        .distinct
      .zipWithIndex
      .map { case (m, i) => (m, i + 1) }
      .toMap
    // return brat output
    Json.obj(
      "text" -> sentenceText,
      "entities" -> mkJsonFromEntities(entities ++ topLevelTBM, tbMentionToId),
      "triggers" -> mkJsonFromEntities(triggers, tbMentionToId),
      "events" -> mkJsonFromEventMentions(events, tbMentionToId)
    )
  }

  def mkJsonFromEntities(mentions: Seq[TextBoundMention], tbmToId: Map[TextBoundMention, Int]): Json.JsValueWrapper = {
    val entities = mentions.map(m => mkJsonFromTextBoundMention(m, tbmToId(m)))
    Json.arr(entities: _*)
  }

  def mkJsonFromTextBoundMention(m: TextBoundMention, i: Int): Json.JsValueWrapper = {
    Json.arr(
      s"T$i",
      m.label,
      Json.arr(Json.arr(m.startOffset, m.endOffset))
    )
  }

  def mkJsonFromEventMentions(ee: Seq[EventMention], tbmToId: Map[TextBoundMention, Int]): Json.JsValueWrapper = {
    var i = 0
    val jsonEvents = for (e <- ee) yield {
      i += 1
      mkJsonFromEventMention(e, i, tbmToId)
    }
    Json.arr(jsonEvents: _*)
  }

  def mkJsonFromEventMention(ev: EventMention, i: Int, tbmToId: Map[TextBoundMention, Int]): Json.JsValueWrapper = {
    Json.arr(
      s"E$i",
      s"T${tbmToId(ev.trigger)}",
      Json.arr(mkArgMentions(ev, tbmToId): _*)
    )
  }

  def mkArgMentions(ev: EventMention, tbmToId: Map[TextBoundMention, Int]): Seq[Json.JsValueWrapper] = {
    val args = for {
      argRole <- ev.arguments.keys
      m <- ev.arguments(argRole)
    } yield {
      val arg = m match {
        case m: TextBoundMention => m
        case m: RelationMention => new TextBoundMention(m.labels, m.tokenInterval, m.sentence, m.document, m.keep, m.foundBy)
        case m: EventMention => m.trigger
      }
      mkArgMention(argRole, s"T${tbmToId(arg)}")
    }
    args.toSeq
  }

  def mkArgMention(argRole: String, id: String): Json.JsValueWrapper = {
    Json.arr(argRole, id)
  }

  def mkJsonFromTokens(doc: Document): Json.JsValueWrapper = {
    var offset = 0

    val tokens = doc.sentences.flatMap { sent =>
      val tokens = sent.words.indices.map(i => mkJsonFromToken(sent, offset, i))
      offset += sent.words.size
      tokens
    }
    Json.arr(tokens: _*)
  }

  def mkJsonFromToken(sent: Sentence, offset: Int, i: Int): Json.JsValueWrapper = {
    Json.arr(
      s"T${offset + i + 1}", // token id (starts at one, not zero)
      sent.tags.get(i), // lets assume that tags are always available
      Json.arr(Json.arr(sent.startOffsets(i), sent.endOffsets(i)))
    )
  }

  def mkJsonFromDependencies(doc: Document): Json.JsValueWrapper = {
    var offset = 1

    val rels = doc.sentences.flatMap { sent =>
      var relId = 0
      val deps = sent.dependencies.get // lets assume that dependencies are always available
      val rels = for {
        governor <- deps.outgoingEdges.indices
        (dependent, label) <- deps.outgoingEdges(governor)
      } yield {
        val json = mkJsonFromDependency(offset + relId, offset + governor, offset + dependent, label)
        relId += 1
        json
      }
      offset += sent.words.size
      rels
    }
    Json.arr(rels: _*)
  }

  def mkJsonFromDependency(relId: Int, governor: Int, dependent: Int, label: String): Json.JsValueWrapper = {
    Json.arr(
      s"R$relId",
      label,
      Json.arr(
        Json.arr("governor", s"T$governor"),
        Json.arr("dependent", s"T$dependent")
      )
    )
  }

  def tab():String = "&nbsp;&nbsp;&nbsp;&nbsp;"
}

object HomeController {


}
