/* 
 * COPYRIGHT (C) 2006-2015 Hitachi Systems CBT S.p.A. Italy. All rights reserved.
 */
package rules.mail;

import it.cbt.mail.ws.client.MailAccountBean;
import it.cbt.mail.ws.client.MailServiceClient;
import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import it.cbt.wr.module.mail.MailManager;
import it.cbt.wr.module.mail.MailModuleImpl;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.script.ScriptException;
import org.slf4j.Logger;

/**
 * This rules is attached to an action in the draft state of an outgoingMail.
 * The code sends a mail using the given account and then deletes it.
 *
 * @author Luca Tagliani
 */
public class gnRlSendOutgoingMailNoArchive implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    String externalURL;
    Entity mail;
    Entity mailAccount;

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        mail = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
        mailAccount = (Entity) mail.getPropertyValue("mailAccount");
    }

    @Override
    public Object run(Map parameters) throws ScriptException {
        HashMap result = new HashMap();
        setParameters(parameters);
        result.put("globalError", false);
        Boolean isp = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
            //Controlli
            Entity accountMail = null;
            Object tmp = mail.getPropertyValue("mailAccount");
            if (tmp == null) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire l'account mail da utilizzare");
                return result;
            }

            accountMail = (Entity) (tmp);
            ArrayList list = (ArrayList) mail.getPropertyValue("to");
            if (list == null || list.size() == 0) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire il destinatario della mail");
                return result;
            }

            tmp = mail.getPropertyValue("body");
            if (tmp == null) {

                result.put("globalError", true);
                result.put("errorMessage", "Inserire il Corpo della mail");
                return result;
            }

            //Imposto in automatico la data di invio e l'indirizzo mittente se non specificati
            Object dInv = mail.getPropertyValue("sentDate");
            logger.info("dInv " + dInv);
            if (dInv == null) {
                Calendar todayCalendar = Calendar.getInstance();
                mail.setProperty("sentDate", todayCalendar);
                logger.info("Aggiunta data " + todayCalendar.getTime());
            }

            list = (ArrayList) mail.getPropertyValue("from");
            logger.info("from " + list.size());
            if (list == null || list.size() == 0) {
                //Recupero l'account usato per l'invio e lo valorizzo con l'indirizzo email dell'account
                list.add(accountMail.getPropertyValue("emailAddress"));
                mail.setProperty("from", list);

                logger.info("Aggiunto emailAddress " + accountMail.getPropertyValue("emailAddress"));
            }
            mail.persist();
            workSession.save();
            // get mailProxyWsdlUrl
            String mailProxyWsdlUrl = (String) mailAccount.getPropertyValue("mailProxyWsdlUrl");
            String mailProxyUserName = (String) mailAccount.getPropertyValue("mailProxyWsdlUsername");
            String mailProxyPassword = (String) mailAccount.getPropertyValue("mailProxyWsdlPassword");
            // connect to remote service using MailManager
            MailModuleImpl mailModule = (MailModuleImpl) workSession.getWorkspace().getInstalledModule("mail");
            MailManager manager = mailModule.getMailManager(workSession, mailProxyWsdlUrl, mailProxyUserName, mailProxyPassword);
            logger.info("Connected to {}", mailProxyWsdlUrl);
            MailServiceClient client = manager.getMailServiceClient();
            MailAccountBean account = client.getMailAccountFromName((String) mailAccount.getPropertyValue(Entity.NAME_PROPERTY));
            manager.sendMail(mail, account);
            // now delete not useful message
            mail.delete();
            workSession.save();
        } catch (Exception ex) {
            String errorMsg = ex.getMessage();
            errorMsg = errorMsg.substring(errorMsg.indexOf(":") + 1);
            if (errorMsg.indexOf("Invalid email") > -1) {
                errorMsg = "Indirizzo Email non valido, controllare i dati e riprovare.";
            } else if (errorMsg.indexOf("Sending failed, attachments") > -1 || errorMsg.indexOf("Sending failed, file") > -1) {
                errorMsg = "Controllare gli allegati e riprovare.";
            } else {
                errorMsg = "Errore durante l'invio della mail, contattare l'amministratore.";
            }
            mail.setProperty("errorMessage", errorMsg);
            mail.persist();
            workSession.save();
            Entity creator = (Entity) mail.getPropertyValue("creator");
            String name = (String) mail.getPropertyValue("name");
            workSession.notifyUser(creator, null, null, 2, "The mail " + name + " has NOT been sent: " + errorMsg, "entity/" + mail.getId());

//            ex.printStackTrace();
//            throw new ScriptException(ex);
            //ERRORE
            result.put("globalError", true);
            result.put("errorMessage", errorMsg);
        } finally {
            result.put("id", mail.getId());
            workSession.setSkipPolicies(isp);
        }
        return result;
    }

}
