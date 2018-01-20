package net.czechit.gcalsync;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.io.InputStream;

import org.apache.log4j.Logger;

public class CalendarSettings
{
    private static String CONFIG_FILE_NAME = "conf/config.properties";

    private final static Logger logger = Logger.getLogger(CalendarSettings.class);
    private Properties prop = null;
    private InputStream input = null;

    private String clientSecret;
    private String applicationName;

    public CalendarSettings() throws IOException
    {
        prop = new Properties();
        input = new FileInputStream(CalendarSettings.CONFIG_FILE_NAME);
        prop.load(input);
        load();
    }

    public void load()
    {
        clientSecret = prop.getProperty("client.secret");
        applicationName = prop.getProperty("client.applicationName");
    }

    /**
     * Returns value from settings
     * @param prefix
     * @param key
     * @return
     * @throws Exception if key doesn't exist
     */
    public String getProperty(String prefix, String key) throws Exception
    {
        String thisKey = getKey(prefix, key);
        String v = prop.getProperty(thisKey);
        if (v == null) {
            Exception e = new Exception(String.format("Settings key %s missing in %s", thisKey, CONFIG_FILE_NAME));
            logger.error("Error in getProperty", e);
            throw e;
        }
        return v;
    }

    /**
     * Returns global value from settings
     * @param key
     * @return
     * @throws Exception if key doesn't exist
     */
    public String getNonmandatoryGlobalProperty(String key) throws Exception
    {
        String v = prop.getProperty(key,"");
        return v;
    }

    /**
     * Returns optional value from settings, if the item doesn't exist, returns empty string.
     * @param prefix
     * @param key
     * @return value from properties or empty string of not exists
     */
    public String getNonmandatoryProperty(String prefix, String key)
    {
        String thisKey = getKey(prefix, key);
        return prop.getProperty(thisKey, "");
    }

    public boolean propertyExists(String prefix, String key)
    {
        String thisKey = getKey(prefix, key);
        return prop.getProperty(thisKey) != null;
    }

    private static String getKey(String prefix, String key)
    {
        return prefix + "." + key;
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
            logger.error("Unable to close CalendarSettings", e);
            e.printStackTrace();
        }
    }

    public String getClientSecret()
    {
        return clientSecret;
    }

    public String getApplicationName()
    {
        return applicationName;
    }
}
