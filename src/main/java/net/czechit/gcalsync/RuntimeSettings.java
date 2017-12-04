package net.czechit.gcalsync;

import org.apache.log4j.Logger;

import java.io.*;
import java.util.Properties;

public class RuntimeSettings
{

    private final static Logger logger = Logger.getLogger(RuntimeSettings.class);
    private Properties prop = null;
    private InputStream input = null;
    private FileOutputStream output = null;

    private String fileName;

    private String lastSyncToken;

    public RuntimeSettings(String fileName)
    {
        try
        {
            this.fileName = fileName;
            prop = new Properties();
            input = new FileInputStream(fileName);
            prop.load(input);
        }
        catch (FileNotFoundException e)
        {
            logger.warn(String.format("Runtime config file %s doesn't exist, lastSyncToken will not be used and all data will be synchronized", fileName), e);
        }
        catch (Exception e)
        {
            logger.error("Problem in loading file " + fileName, e);
        }
        load();
    }

    public void load()
    {
        lastSyncToken = (prop != null) ? prop.getProperty("lastSyncToken", "") : "";
    }

    public void save()
    {
        if (lastSyncToken != null) prop.setProperty("lastSyncToken", lastSyncToken);

        output = null;
        try
        {
            output = new FileOutputStream(fileName);
            prop.store(output, "Runtime settings for CalendarSync application. If you need clean run with synchronizing all events, just delete this file.");
        }
        catch (IOException e)
        {
            logger.error(String.format("Error in saving config file %s", fileName), e);
        }
        finally {
            if (output != null)
            {
                try {
                    output.close();
                }
                catch (IOException e)
                {
                    logger.error("Error in closing file", e);
                }
            }
        }
    }

    public void close()
    {
        try
        {
            if (input != null)
                input.close();
        }
        catch (IOException e)
        {
            logger.error("Unable to close settings", e);
            e.printStackTrace();
        }
    }

    public String getLastSyncToken()
    {
        return lastSyncToken;
    }

    public void setLastSyncToken(String lastSyncToken)
    {
        this.lastSyncToken = lastSyncToken;
    }
}
