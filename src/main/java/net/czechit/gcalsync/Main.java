package net.czechit.gcalsync;

import com.google.api.client.auth.oauth2.Credential;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import java.io.IOException;

// https://developers.google.com/google-apps/calendar/quickstart/java

public class Main
{

    final static Logger logger = Logger.getLogger(Main.class);

    private CalendarSettings calendarSettings;

    public static void main( String[] args )
    {
        PropertyConfigurator.configure("conf/log4j.properties");
        //OneWaySync.fixId("rva3c7gdfup1gp6hb408hkeu4c_R20171018T130000");
        mainRoutine();
    }

    public static void mainRoutine()
    {
        Main mainHandler = null;
        try
        {
            mainHandler = new Main();
            mainHandler.runAllSync();
        } catch (Throwable e)
        {
            logger.error("Exception during initialization of the application", e);
            return;
        }
    }

    public Main() throws IOException
    {
        calendarSettings = new CalendarSettings();
    }

    public void runAllSync()
    {
        int i = 1;
        while (calendarSettings.propertyExists(String.format("sync.%d", i), "source"))
        {
            String prefix = String.format("sync.%d", i);
            runSync(prefix);
            i++;
        }
    }

    private void runSync(String prefix)
    {
            OneWaySync sync = null;
            try
            {
                sync = new OneWaySync(calendarSettings, prefix);
                sync.sync();
            }
            catch (Throwable e)
            {
                logger.error(String.format("Exception during synchronization, key %s", prefix), e);
            }
            finally
            {
                if (sync != null)
                    sync.close();
            }
    }
}
