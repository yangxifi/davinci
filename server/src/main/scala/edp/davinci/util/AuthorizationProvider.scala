/*-
 * <<
 * Davinci
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */





package edp.davinci.util

import akka.http.scaladsl.server.directives.Credentials
import edp.davinci.ModuleInstance
import edp.davinci.module.DbModule
import edp.davinci.module.DbModule._
import edp.davinci.persistence.entities.{LoginClass, User4Query, User}
import edp.davinci.rest.SessionClass
import edp.davinci.util.LDAPValidate.validate
import edp.davinci.util.ResponseUtils.currentTime
import org.apache.log4j.Logger
import slick.jdbc.MySQLProfile.api._

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

abstract class AuthorizationError(val statusCode: Int = 401, val desc: String = "authentication error") extends Exception

class UserNotFoundError(statusCode: Int = 404, desc: String = "user not found") extends AuthorizationError(statusCode, desc)

class passwordError(statusCode: Int = 400, desc: String = "password is wrong") extends AuthorizationError(statusCode, desc)

object AuthorizationProvider {
  private lazy val module = ModuleInstance.getModule
  private lazy val logger = Logger.getLogger(this.getClass)
  lazy val realm = "davinci"

  def createSessionClass(login: LoginClass, enableLDAP: Boolean): Future[Either[AuthorizationError, (SessionClass, User4Query)] with Product with Serializable] = {
    try {
      val user = if (enableLDAP && validate(login.username, login.password)) findUserByLDAP(login) else findUser(login)
      user.flatMap {
        user =>
          DbModule.db.run(module.relUserGroupQuery.filter(_.user_id === user.id).map(_.group_id).distinct.result)
            .map {
              relSeq =>

                val groupIdList = new ListBuffer[Long]
                if (relSeq.nonEmpty) relSeq.foreach(groupIdList += _)
                val userInfo = User4Query(user.id, user.email, user.title, user.name, user.admin)
                val session = SessionClass(user.id, groupIdList.toList, user.admin)
                (session, userInfo)
            }
      }.map(Right(_)).recover {
        case e: AuthorizationError =>
          logger.error("createSessionClass error", e)
          Left(e)
      }
    } catch {
      case e: AuthorizationError =>
        logger.error("createSessionClass error", e)
        Future.successful(Left(e))
    }

  }

  def findUserByLDAP(login: LoginClass): Future[User] = {
    val ldapUser = User(0, login.username, login.password, "", login.username, admin = false, active = true, currentTime, 0, currentTime, 0)
    module.userDal.findByFilter(user => user.email === login.username && user.active === true).map[User] {
      userSeq =>
        userSeq.headOption match {
          case Some(_) =>
            db.run(module.userQuery.filter(_.email === login.username).map(_.password).update(login.password))
            ldapUser
          case None =>
            logger.info("user not found")
            module.userDal.insert(ldapUser)
            ldapUser
        }
    }
  }


  def authorize(credentials: Credentials): Future[Option[SessionClass]] =
    credentials match {
      case p@Credentials.Provided(token) =>
        validateToken(token)
      case _ => Future.successful(None)
    }


  private def findUser(login: LoginClass): Future[User] = {
    module.userDal.findByFilter(user => user.email === login.username && user.active === true).map[User] {
      userSeq =>
        println(userSeq.headOption)
        userSeq.headOption match {
          case Some(user) =>
            if (verifyPwd(user.password, login.password)) user
            else throw new passwordError()
          case None =>
            logger.info("user not found")
            throw new UserNotFoundError()
        }
    }
  }

  def validateToken(token: String): Future[Option[SessionClass]] = {
    try {
      val session = JwtSupport.decodeToken(token)
      Future.successful(Some(session))
    } catch {
      case e: Exception =>
        logger.error("validateToken error", e)
        Future.successful(None)
    }
  }


  private def verifyPwd(storePass: String, pass: String): Boolean = {
    //    pass.isBcrypted(storePass)
    if (PasswordHash.validatePassword(pass,storePass)) true
    else false
  }


}