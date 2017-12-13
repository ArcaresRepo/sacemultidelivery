package rules.arcares;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.WrApiUtils;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Action;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class RuleWFrecuperaDati implements WrRuleClassBody {

    WorkSession workSession; // Required
    Logger logger; // Required
    Entity entity; // Required
    
    Connection con = null;
    private Statement updateStatemant;
    private final String updateDBArcaresRuleName = "UpdateDBArcares";
    
    void setParameters(Map parameters) {
        workSession = (WorkSession) parameters.get("workSession");
        logger = (Logger) parameters.get("logger");
        entity = (Entity) ((List) parameters.get(Action.ENTITIES_PARAMETER)).get(0);
    }

    public Object run(Map parameters) {
        HashMap retMap = new HashMap();
        setParameters(parameters);

        boolean wasSkippingPolicies = workSession.isSkippingPolicies();
        try {
            workSession.setSkipPolicies(true);
            // recupera opzioni di collegamento al DB di Arcares
            String query = "/jcr:root//element(*,opzioneArcares)";
            QueryManager qm = workSession.getQueryManager();
            List<Entity> list = qm.executeQuery(query).getResults();
            
            boolean taskAutomatico = (Boolean) entity.getPropertyValue("taskAutomatico");
            logger.info("taskAutomatico: {}", taskAutomatico);
            if (list != null && !list.isEmpty()) {

                String select = "SELECT * "
                        + "FROM WRDC_HEADER AS HEADE "
                        + "WHERE HEADE.COD_STATO = '0'";
                logger.info("RuleWrecuperaDati: select: " + select);
                con = getConnectionForArcaresDB();

                updateStatemant = con.createStatement();
                ResultSet res = updateStatemant.executeQuery(select);
                
                while (res.next()) {
                    // fase creazione del singolo header
                    String idFlusso = res.getString("ID_FLUSSO");
                    String desTipoDoc = res.getString("DES_TIPO_DOC");
                    
                    logger.info("RuleWrecuperaDati: ID_FLUSSO: " + idFlusso);
                    
                    Entity header = workSession.createNewEntity("header", entity);
                    if (header != null) {
                        header.setProperty("name", "Flusso " + idFlusso);
                        header.setProperty("idFlusso", idFlusso);
                        header.setProperty("stato", "1");
                        header.setProperty("desTipoDoc", desTipoDoc);
                        String strRuleName = "SaveHeader";
                        HashMap params = new HashMap();
                        params.put("workSession", workSession);
                        ArrayList entities = new ArrayList();
                        entities.add(header);
                        params.put("entities", entities);//Action.ENTITIES_PARAMETER
                        params.put("logger", logger);
                        logger.info("RuleWrecuperaDati: Calling externalRule " + strRuleName);
                        WrApiUtils.evaluateRule(workSession, strRuleName, params);
                        
                        String update = "UPDATE WRDC_HEADER SET TMS_ELAB = CURRENT_TIMESTAMP, COD_STATO = '1' WHERE ID_FLUSSO = '" + idFlusso + "'";
                        HashMap paramsUpdate = new HashMap();
                        paramsUpdate.put("workSession", workSession);
                        paramsUpdate.put("logger", logger);
                        paramsUpdate.put("query", update);
                        logger.info("calling externalRule " + updateDBArcaresRuleName);
                        WrApiUtils.evaluateRule(workSession, updateDBArcaresRuleName, paramsUpdate);
                    }
                }
                res.close();
            }
        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.error(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("Errore Connessione DB ");
        } finally {
            workSession.setSkipPolicies(wasSkippingPolicies);
            
            if (updateStatemant != null) {
                try {
                    updateStatemant.close();
                } catch (SQLException e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(e));
                    logger.debug(ExceptionUtils.getStackTrace(e));
                }
            }
            if (con != null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    logger.error("RuleWrecuperaDati Database Connection Error: {}", ExceptionUtils.getMessage(e));
                }
            }
        }
        return null;
    }
    
    private Connection getConnectionForArcaresDB() {
        // recupera opzioni di collegamento al DB di Arcares
        StringBuilder query = new StringBuilder().append("/jcr:root").append("//element(*,opzioneArcares)");
        QueryManager qm = workSession.getQueryManager();
        List<Entity> list = qm.executeQuery(query.toString()).getResults();
        Entity optArcares = (Entity) list.get(0);

        // connessione al db
        Connection con = null;
        try {
            Class.forName((String) optArcares.getPropertyValue("jdbcDriver"));
            con = DriverManager.getConnection((String) optArcares.getPropertyValue("dataSource"),
                    (String) optArcares.getPropertyValue("nomeUtente"),
                    (String) optArcares.getPropertyValue("password"));
//		} catch (ModelException | SQLException | ClassNotFoundException e) {
        } catch (Exception e) {
            logger.error("error: {}", ExceptionUtils.getMessage(e));
            logger.debug(ExceptionUtils.getStackTrace(e));
            throw new RuntimeException("ERRORE IN RuleWrecuperaDati: " + e.getMessage(), e);
        }
        return con;
    }
}
