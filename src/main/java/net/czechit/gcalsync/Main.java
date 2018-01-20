package net.czechit.gcalsync;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.google.api.client.auth.oauth2.Credential;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

// https://developers.google.com/google-apps/calendar/quickstart/java

public class Main
{

    final static Logger logger = LoggerFactory.getLogger(Main.class);

    private CalendarSettings calendarSettings;

    public static void main( String[] args ) throws JoranException {
        configureLogback();
        mainRoutine();
    }

    public static void configureLogback() throws JoranException {
        SLF4JBridgeHandler.install();
        // assume SLF4J is bound to logback in the current environment
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        context.reset();
        configurator.doConfigure("conf/logback.xml");
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
