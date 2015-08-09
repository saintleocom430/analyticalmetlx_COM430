package com.metl.model

import com.metl.liftAuthenticator._
import monash.SAML._

import com.metl.cas._
import com.metl.data._
import com.metl.metl2011._
import com.metl.utils._

import _root_.net.liftweb.util._
import Helpers._
import _root_.net.liftweb.common._
import _root_.net.liftweb.http._
import _root_.net.liftweb.http.rest._
import _root_.net.liftweb.http.provider._
import _root_.net.liftweb.sitemap._
import _root_.net.liftweb.sitemap.Loc._
import com.metl.snippet._
import com.metl.view._
import com.metl.cas._
import com.metl.auth._
import com.metl.h2._

import net.liftweb.util.Props
import com.mongodb._
import net.liftweb.mongodb._

object StackConfiguration {
  def setup = {
    val mo = new MongoOptions
    mo.socketTimeout = 10000
    mo.socketKeepAlive = true
    val srvr = new ServerAddress("127.0.0.1", 27017)

    MongoDB.defineDb(DefaultMongoIdentifier, new Mongo(srvr, mo), "stack")

    //construct standingCache from DB
    com.metl.model.Reputation.populateStandingMap
    //construct LiftActors for topics with history from DB
    com.metl.model.TopicManager.preloadAllTopics
    //ensure that the default topic is available
    com.metl.model.Topic.getDefaultValue
  }
}

object MeTLXConfiguration {
  protected var configs:Map[String,Tuple2[ServerConfiguration,RoomProvider]] = Map.empty[String,Tuple2[ServerConfiguration,RoomProvider]]
  val updateGlobalFunc = (c:Conversation) => {
    getRoom("global",c.server.name) ! ServerToLocalMeTLStanza(MeTLCommand(c.server,c.author,new java.util.Date().getTime,"/UPDATE_CONVERSATION_DETAILS",List(c.jid.toString)))
  }
  def getRoomProvider(name:String) = {
    new HistoryCachingRoomProvider(name)
  }
  def setupServersFromFile(filePath:String) = {
    def setupUserWithSamlState(la: LiftAuthStateData): Unit = {
      if ( la.authenticated ) {
        Globals.currentUser(la.username)
        Globals.casState.set(new CASStateData(true,la.username,la.eligibleGroups,la.informationGroups))
      }
    }
    LiftAuthAuthentication.attachAuthenticator(
      new SAMLAuthenticationSystem(
        new SAMLAuthenticator(
          alreadyLoggedIn = () => Globals.casState.authenticated,
          onSuccess = setupUserWithSamlState _,
          samlConfiguration = Globals.getSAMLconfiguration
        )
      )
    )

    MeTL2011ServerConfiguration.initialize
    LocalH2ServerConfiguration.initialize
    ServerConfiguration.loadServerConfigsFromFile(filePath,updateGlobalFunc)
    val servers = ServerConfiguration.getServerConfigurations
    configs = Map(servers.map(c => (c.name,(c,getRoomProvider(c.name)))):_*)
  }
  def initializeSystem = {
    Props.mode match {
      case Props.RunModes.Production => Globals.isDevMode = false
      case _=> Globals.isDevMode = true
    }
    setupServersFromFile(Globals.configurationFileLocation)
    // Setup RESTful endpoints (these are in view/Endpoints.scala)
    LiftRules.statelessDispatchTable.prepend(MeTLRestHelper)
    LiftRules.dispatch.append(MeTLStatefulRestHelper)
    LiftRules.statelessDispatchTable.prepend(WebMeTLRestHelper)
    LiftRules.dispatch.append(WebMeTLStatefulRestHelper)
    configs.values.foreach(c => {
      getRoom("global",c._1.name)
      println("%s is now ready for use (%s)".format(c._1.name,c._1.isReady))
    })
    configs.values.foreach(c => LiftRules.unloadHooks.append(c._1.shutdown _))
    StackConfiguration.setup
    println(configs)
  }
  def getRoom(jid:String,configName:String) = {
    configs(configName)._2.get(jid)
  }
}

class TransientLoopbackAdaptor(configName:String,onConversationDetailsUpdated:Conversation=>Unit) extends ServerConfiguration(configName,"no_host",onConversationDetailsUpdated){
  val serializer = new PassthroughSerializer
  val messageBusProvider = new LoopbackMessageBusProvider
  override def getMessageBus(d:MessageBusDefinition) = messageBusProvider.getMessageBus(d)
  override def getHistory(jid:String) = History.empty
  override def getConversationForSlide(slideJid:String):String = ""
  override def searchForConversation(query:String) = List.empty[Conversation]
  override def detailsOfConversation(jid:String) = Conversation.empty
  override def createConversation(title:String,author:String) = Conversation.empty
  override def deleteConversation(jid:String):Conversation = Conversation.empty
  override def renameConversation(jid:String,newTitle:String):Conversation = Conversation.empty
  override def changePermissions(jid:String,newPermissions:Permissions):Conversation = Conversation.empty
  override def updateSubjectOfConversation(jid:String,newSubject:String):Conversation = Conversation.empty
  override def addSlideAtIndexOfConversation(jid:String,index:Int):Conversation = Conversation.empty
  override def reorderSlidesOfConversation(jid:String,newSlides:List[Slide]):Conversation = Conversation.empty
  override def getImage(jid:String,identity:String) = MeTLImage.empty
  override def getResource(url:String) = Array.empty[Byte]
  override def postResource(jid:String,userProposedId:String,data:Array[Byte]):String = ""
}
