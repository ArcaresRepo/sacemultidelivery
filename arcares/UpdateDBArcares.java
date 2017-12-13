/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rules.arcares;

import it.cbt.wr.api.WorkSession;
import it.cbt.wr.api.service.repository.QueryManager;
import it.cbt.wr.api.service.repository.entities.Entity;
import it.cbt.wr.core.script.janino.WrRuleClassBody;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

/**
 *
 * @author mori
 */
public class UpdateDBArcares implements WrRuleClassBody {

    WorkSession ws; // Required
    Logger logger;	// Required
    String query = null;

    Connection con = null;
    private Statement updateStatemant;

    void setParameters(Map parameters) {
        logger.info("UpdateDBArcares: setParameters...");
        ws = (WorkSession) parameters.get("workSession");
        query = (String) parameters.get("query");
    }

    public Object run(Map parameters) {
        logger = (Logger) parameters.get("logger");
        logger.info("UpdateDBArcares: Inizio");
        setParameters(parameters);
        boolean wasSkippingPolicies = ws.isSkippingPolicies();
        HashMap result = new HashMap();
        result.put("globalError", false);
        try {
            ws.setSkipPolicies(Boolean.TRUE);
            
            con = getConnectionForArcaresDB();            
            updateStatemant = con.createStatement();

            logger.info("UpdateDBArcares query: {}", query);
            updateStatemant.executeUpdate(query);
            
            logger.info("UpdateDBArcares Executed Successfully");
            
        } catch (Exception ex) {
            String errorMsg = ex.getMessage();
            errorMsg = errorMsg.substring(errorMsg.indexOf(":") + 1);
            logger.warn("UpdateDBArcares Error: Executing action rule " + getClass().getName(), ex);
            logger.error("UpdateDBArcares Error: {}", ExceptionUtils.getMessage(ex));
            logger.error(ExceptionUtils.getStackTrace(ex));
            result.put("globalError", true);
            result.put("errorMessage", errorMsg);
        } finally {
            ws.setSkipPolicies(wasSkippingPolicies);
            if (updateStatemant != null) {
                try {
                    updateStatemant.close();
                } catch (SQLException e) {
                    logger.error("error: {}", ExceptionUtils.getMessage(e));
                    logger.error(ExceptionUtils.getStackTrace(e));
                }
            }
            if (con != null) {
                try {
                    con.close();
                } catch (SQLException e) {
                    logger.error("UpdateDBArcares Database Connection Error: {}", ExceptionUtils.getMessage(e));
                }
            }
            logger.info("UpdateDBArcares: End");
        }
        return result;
    }

    private Connection getConnectionForArcaresDB() {
        // recupera opzioni di collegamento al DB di Arcares
        StringBuilder query = new StringBuilder().append("/jcr:root").append("//element(*,opzioneArcares)");
        QueryManager qm = ws.getQueryManager();
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
            throw new RuntimeException("ERRORE IN UpdateDBArcares: " + e.getMessage(), e);
        }
        return con;
    }

}
