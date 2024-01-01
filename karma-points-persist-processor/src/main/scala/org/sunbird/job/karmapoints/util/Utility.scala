package org.sunbird.job.karmapoints.util

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.{Insert, QueryBuilder, Select}
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.sunbird.job.karmapoints.task.KarmaPointsProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, ScalaJsonUtil}
import org.sunbird.job.Metrics

import java.util
import java.util.{Date}
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.collection.immutable.Map

object Utility {
  private val logger = LoggerFactory.getLogger("org.sunbird.job.karmapoints.util.Utility")
  lazy private val mapper: ObjectMapper = new ObjectMapper()

   def insertKarmaPoints(userId : String, contextType : String,operationType:String,contextId:String, points:Int,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil)(implicit metrics: Metrics): Unit = {
    insertKarmaPoints(userId, contextType,operationType,contextId, points,"",config, cassandraUtil)(metrics)
  }

  def upsertKarmaPoints(userId : String, contextType : String,operationType:String,contextId:String,points:Int,
                        addInfo:String,credit_date : Long,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil): Boolean = {
    val query: Insert = QueryBuilder.insertInto(config.sunbird_keyspace, config.user_karma_points_table)
    query.value(config.USER_ID, userId)
    query.value(config.CREDIT_DATE,  credit_date)
    query.value(config.CONTEXT_TYPE, contextType)
    query.value(config.OPERATION_TYPE, operationType)
    query.value(config.CONTEXT_ID, contextId)
    query.value(config.ADD_INFO, addInfo)
    query.value(config.POINTS,points)
    cassandraUtil.upsert(query.toString)
  }
   def insertKarmaPoints( userId : String, contextType : String,operationType:String,contextId:String,points:Int,
                          addInfo:String,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil)(implicit metrics: Metrics): Unit = {
     val credit_date = System.currentTimeMillis()
     val result = upsertKarmaPoints(userId, contextType, operationType, contextId, points, addInfo, credit_date,config, cassandraUtil)
    if (result) {
      insertKarmaCreditLookUp(userId, contextType,operationType,contextId,credit_date,config, cassandraUtil)
      metrics.incCounter(config.dbUpdateCount)
    } else {
      val msg = "Database update has failed for userId :- "+userId +" ,contextType : "+
        contextType +",operationType : " + operationType+", contextId :"+ contextId +", Points" + points
      logger.error(msg)
      throw new Exception(msg)
    }
  }

   def isEntryAlreadyExist(userId: String, contextType: String, operationType: String, contextId: String,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil): Boolean = {
     val karmaPointsLookUp = karmaPointslookup(userId, contextType, operationType, contextId,config, cassandraUtil)
     if(karmaPointsLookUp.size() < 1)
       return false
     val credit_date = karmaPointsLookUp.get(0).getObject(config.DB_COLUMN_CREDIT_DATE).asInstanceOf[Date]
     val result_ = karmaPointsEntry(credit_date,userId, contextType, operationType, contextId,config, cassandraUtil)
    result_.size() > 0
  }

  def karmaPointsEntry(credit_date:Date ,userId: String, contextType: String, operationType: String, contextId: String
                          ,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil):  util.List[Row] = {
    val karma_query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_points_table)
    karma_query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userId))
    karma_query.where(QueryBuilder.eq(config.DB_COLUMN_CREDIT_DATE, credit_date))
    karma_query.where(QueryBuilder.eq(config.DB_COLUMN_CONTEXT_TYPE, contextType))
    karma_query.where(QueryBuilder.eq(config.DB_COLUMN_OPERATION_TYPE, operationType))
    karma_query.where(QueryBuilder.eq(config.DB_COLUMN_CONTEXT_ID, contextId))
    cassandraUtil.find(karma_query.toString)
  }

  def isFirstEnrolment(batchid: String, userid: String, config: KarmaPointsProcessorConfig, cassandraUtil: CassandraUtil): Boolean = {
    val enrol_lookup_query: Select = QueryBuilder.select().from(config.sunbird_courses_keyspace, config.user_enrolment_batch_lookup_table)
    enrol_lookup_query.where(QueryBuilder.eq(config.DB_COLUMN_BATCH_ID, batchid))
    enrol_lookup_query.where(QueryBuilder.eq(config.DB_COLUMN_USERID, userid))
    cassandraUtil.find(enrol_lookup_query.toString).size() < 2
  }

  def userRootOrgId(userid: String, config: KarmaPointsProcessorConfig, cassandraUtil: CassandraUtil): String = {
    val enrol_lookup_query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_table)
    val columnNames: util.List[String] = new util.ArrayList[String]()
    columnNames.add("rootorgid")
    enrol_lookup_query.where(QueryBuilder.eq(config.ID, userid))
    cassandraUtil.find(enrol_lookup_query.toString).get(0).getString("rootorgid")
  }

  def karmaPointslookup(userId: String, contextType: String, operationType: String, contextId: String,
                        config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil): util.List[Row] = {
    val query: Select = QueryBuilder.select().from(config.sunbird_keyspace, config.user_karma_points_credit_lookup_table)
    query.where(QueryBuilder.eq(config.DB_COLUMN_USER_KARMA_POINTS_KEY, userId + config.UNDER_SCORE + contextType + config.UNDER_SCORE  + contextId))
    query.where(QueryBuilder.eq(config.DB_COLUMN_OPERATION_TYPE, operationType))
    cassandraUtil.find(query.toString)
  }

   def insertKarmaCreditLookUp(userId : String, contextType : String,operationType:String,contextId:String, credit_date: Long,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil): Boolean = {
    val query: Insert = QueryBuilder.insertInto(config.sunbird_keyspace, config.user_karma_points_credit_lookup_table)
    query.value(config.DB_COLUMN_USER_KARMA_POINTS_KEY, userId + "_"+contextType+"_"+ contextId)
    query.value(config.DB_COLUMN_OPERATION_TYPE, operationType)
    query.value(config.DB_COLUMN_CREDIT_DATE, credit_date)
    cassandraUtil.upsert(query.toString)
  }

   def isAssessmentExist(hierarchy:java.util.Map[String, AnyRef],config: KarmaPointsProcessorConfig)(implicit metrics: Metrics):Boolean = {
    var result:Boolean = false;
    val childrenMap = hierarchy.get(config.CHILDREN).asInstanceOf[util.ArrayList[util.HashMap[String, AnyRef]]];
    for (children <- childrenMap) {
      val primaryCategory = children.get(config.primaryCategory)
      if (primaryCategory == config.COURSE_ASSESSMENT) result = true
    }
    result;
  }

   def isACBP(courseId: String, httpUtil: HttpUtil,config: KarmaPointsProcessorConfig,headers : Map[String, String])(implicit metrics: Metrics):Boolean = {
    isCbpPlan(courseId,headers)(metrics, config, httpUtil)
  }
   def fetchContentHierarchy(courseId: String,config: KarmaPointsProcessorConfig,cassandraUtil: CassandraUtil)(implicit metrics: Metrics): util.HashMap[String,AnyRef] = {
    val selectWhere: Select.Where = QueryBuilder.select(config.Hierarchy)
      .from(config.content_hierarchy_KeySpace, config.content_hierarchy_table).where()
    selectWhere.and(QueryBuilder.eq(config.identifier, courseId))
    metrics.incCounter(config.dbReadCount)
    val courseList = cassandraUtil.find(selectWhere.toString)
    if (null != courseList) {
      val hierarchy = courseList.get(0).getString("hierarchy")
      mapper.readValue(hierarchy, classOf[java.util.Map[String, AnyRef]]).asInstanceOf[util.HashMap[String, AnyRef]]
    }else
    {
      new util.HashMap[String, AnyRef]()
    }
  }

  def isCbpPlan(courseId: String,headers : Map[String, String])(
    metrics: Metrics,
    config: KarmaPointsProcessorConfig,
    httpUtil: HttpUtil
  ): Boolean= {
    val url =
      config.cbPlanReadUser
    val response = getAPICall(url,headers)(config, httpUtil, metrics)
    val identifiers: List[AnyRef] = response.get(config.CONTENT) match {
      case Some(content) =>
        content.asInstanceOf[List[Map[String, AnyRef]]].flatMap { contentItem =>
          contentItem.get(config.CONTENT_LIST) match {
            case Some(contentList) =>
              contentList.asInstanceOf[List[Map[String, AnyRef]]].flatMap(_.get(config.IDENTIFIER))
            case None =>
              List.empty[AnyRef] // or handle the case when "contentList" is not present in the response
          }
        }
      case None =>
        List.empty[AnyRef] // or handle the case when "content" is not present in the response
    }
    identifiers.contains(courseId)
  }

  def getAPICall(url: String, headers : Map[String, String])(
    config: KarmaPointsProcessorConfig,
    httpUtil: HttpUtil,
    metrics: Metrics
  ): Map[String, AnyRef] = {
    val response = httpUtil.get(url, headers)
    if (200 == response.status) {
      ScalaJsonUtil
        .deserialize[Map[String, AnyRef]](response.body)
        .getOrElse(config.RESULT, Map[String, AnyRef]())
        .asInstanceOf[Map[String, AnyRef]]
    } else if (
      400 == response.status && response.body.contains(
        config.userAccBlockedErrCode
      )
    ) {
      metrics.incCounter(config.skippedEventCount)
      logger.error(
        s"Error while fetching user details for ${url}: " + response.status + " :: " + response.body
      )
      Map[String, AnyRef]()
    } else {
      throw new Exception(
        s"Error from get API : ${url}, with response: ${response}"
      )
    }
  }
}
