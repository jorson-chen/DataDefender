package com.strider.dataanonymizer;

import com.strider.dataanonymizer.database.DBConnectionFactory;
import com.strider.dataanonymizer.database.DatabaseAnonymizerException;
import com.strider.dataanonymizer.database.IDBConnection;
import com.strider.dataanonymizer.functions.Functions;
import static com.strider.dataanonymizer.functions.Functions.init;
import com.strider.dataanonymizer.requirement.Column;
import com.strider.dataanonymizer.requirement.Parameter;
import com.strider.dataanonymizer.requirement.Requirement;
import com.strider.dataanonymizer.requirement.Table;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import static java.lang.Integer.parseInt;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;
import javax.xml.bind.JAXBContext;
import static javax.xml.bind.JAXBContext.newInstance;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import org.apache.log4j.Logger;
import static org.apache.log4j.Logger.getLogger;


/**
 * Entry point for RDBMS data anonymizer
 * 
 * @author Armenak Grigoryan
 */
public class DatabaseAnonymizer implements IAnonymizer { 
    
    private static Logger log = getLogger(DatabaseAnonymizer.class);

    @Override
    public void anonymize(Properties databaseProperties, Properties anonymizerProperties) 
    throws DatabaseAnonymizerException{

        log.info("Connecting to database");        
        IDBConnection dbConnection = DBConnectionFactory.createDBConnection(databaseProperties);
        Connection connection = dbConnection.connect(databaseProperties);
        
        String requirementFile = anonymizerProperties.getProperty("requirement");
        int batchSize = parseInt(anonymizerProperties.getProperty("batch_size"));
        
        
        // Now we collect data from the requirement
        Requirement requirement = null;
        try {
            JAXBContext jc = newInstance(Requirement.class);
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            requirement = (Requirement) unmarshaller.unmarshal(new FileInputStream(new File(requirementFile)));
        } catch (JAXBException je) {
            log.error(je.toString());
            throw new DatabaseAnonymizerException(je.toString(), je);
        } catch (FileNotFoundException ex) {
            java.util.logging.Logger.getLogger(DatabaseAnonymizer.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Initializing static data in Functions
        init();
        
        // Iterate over the requirement
        log.info("Anonymizing data for client " + requirement.getClient() + " Version " + requirement.getVersion());
        
        for(Table table : requirement.getTables()) {
            log.info("Table [" + table.getName() + "]. Start ...");
            
            // Here we start building SQL query            
            PreparedStatement pstmt = null;
            Statement stmt = null;
            ResultSet rs = null;
            StringBuilder sql = new StringBuilder("UPDATE " + table.getName() + " SET ");
            int batchCounter = 0;            
            
            // First iteration over columns to build the UPDATE statement
            for(Column column : table.getColumns()) {
                sql.append(column.getName()).append(" = ?,");
            }
            // remove training ","
            if (sql.length() > 0) {
                sql.setLength(sql.length() - 1);
            }
            
            sql.append(" WHERE ").append(table.getPKey()).append(" = ?");
            final String updateString = sql.toString();
            
            try {
                stmt = connection.createStatement();
                rs = stmt.executeQuery(String.format("SELECT id FROM %s ", table.getName()));
                pstmt = connection.prepareStatement(updateString);
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int index = 0;
                    
                    for(Column column : table.getColumns()) {
                        String function = column.getFunction();
                        if (function == null || function.equals("")) {
                            log.warn("    Function is not defined for column [" + column + "]. Moving to the next column.");
                        } else {
                            try {
                                Class clazz = Functions.class;
                                if (column.getParameters() == null) {
                                    Method method = clazz.getMethod(function,null);
                                    pstmt.setString(++index, method.invoke(null).toString());
                                } else {
                                    Method method = clazz.getMethod(function, String[].class);
                                    String[] stringParams = new String[column.getParameters().size()];
                                    for(int i=0; i<=column.getParameters().size()-1;i++) {
                                        Parameter parameter = column.getParameters().get(i);
                                        stringParams[i] = parameter.getValue().toString();
                                    }
                                    Object result = method.invoke(null, (Object)stringParams);
                                    pstmt.setString(++index, result.toString());
                                }
                            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                                ex.printStackTrace();
                                log.error(ex.toString());
                                try {
                                    stmt.close();
                                    if (pstmt != null) {
                                        pstmt.close();
                                    }
                                    rs.close();
                                } catch (SQLException sqlex) {
                                    log.error(sqlex.toString());
                                }                                                
                            }
                        }
                    }
                    pstmt.setInt(++index, id);
                    pstmt.addBatch();
                    batchCounter++;
                    if (batchCounter == batchSize) {
                        pstmt.executeBatch();
                        connection.commit();
                        batchCounter = 0;
                    }
                }
                pstmt.executeBatch();
                connection.commit();
                stmt.close();
                pstmt.close();
                rs.close();
            } catch (SQLException sqle) {
                log.error(sqle.toString());
                try {
                    if (stmt != null) {
                        stmt.close();
                    }
                    if (pstmt != null) {
                        pstmt.close();
                    }
                    if (rs != null) {
                        rs.close();
                    }
                } catch (SQLException sqlex) {
                    log.error(sqlex.toString());
                }                
            }
            log.info("Table " + table.getName() + ". End ...");
            log.info("");
        }       
    }
}