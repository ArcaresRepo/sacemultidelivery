package rules.mail;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.script.ScriptException;
import org.slf4j.Logger;

/**
 * This rules is attached to an action in the draft state of an outgoingMail.
 * The code sends a mail using the given account and then ends its workflow,
 * archiving it.
 *
 * @author Luca Tagliani
 */
public class gnRlSendOutgoingMailArchiveNoWait implements WrRuleClassBody {

    WorkSession workSession;
    Logger logger;
    List mails;

    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        mails = (List) parameters.get(Action.ENTITIES_PARAMETER);
    }

    @Override
    public Object run(Map parameters) throws ScriptException {
        HashMap result = new HashMap();
        result.put("globalError", false);
        setParameters(parameters);
        Boolean isp = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
            //eseguo su tutte le mails

            for (int i = 0; i < mails.size(); i++) {
                HashMap params = new HashMap();

                params.putAll(parameters);
                Entity mail = (Entity) mails.get(i);
                logger.info("Invio mail: " + mail.getId());
                ArrayList newMailList = new ArrayList();
                newMailList.add(mail);
                params.put(Action.ENTITIES_PARAMETER, newMailList);
                WrApiUtils.evaluateBackgroundRule(workSession, "gnRlSendOutgoingMailArchive", params);
                
            }

        } finally {
            workSession.setSkipPolicies(isp);
        }
        return result;
    }

}
