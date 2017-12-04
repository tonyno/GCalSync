package net.czechit.gcalsync;

//https://developers.google.com/google-apps/calendar/quickstart/java

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.ColorDefinition;
import com.google.api.services.calendar.model.Colors;
import com.jcabi.aspects.LogExceptions;
import com.jcabi.aspects.Loggable;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CalendarConnection
{

    final static Logger logger = Logger.getLogger(CalendarConnection.class);

    private com.google.api.services.calendar.Calendar service;

    /**
     * Settings for this calendar
     */
    private CalendarSettings settings;

    /**
     * Prefix of all keys in properties (CalendarSettings), something like: calendar.1 (without last dot)
     */
    private String settingsPrefix;


    private String settingsCredentialsDir;
    private String settingsAccountName;

    /**
     * Application name.
     */
    private String applicationName;

    /**
     * Directory to store user credentials for this application.
     */
    private java.io.File dataStoreDir;

    /**
     * Global instance of the {@link FileDataStoreFactory}.
     */
    private FileDataStoreFactory fileDataStoreFactory;

    /**
     * Global instance of the JSON factory.
     */
    private static final JsonFactory JSON_FACTORY = JacksonFactory
            .getDefaultInstance();

    /**
     * Global instance of the HTTP transport.
     */
    private HttpTransport httpTransport;

    /**
     * Global instance of the scopes required by this quickstart.
     */
    private List<String> scopes;


    public CalendarConnection(CalendarSettings settings, String settingsPrefix) throws Throwable
    {
        this.settings = settings;
        this.settingsPrefix = settingsPrefix;

        this.settingsCredentialsDir = settings.getProperty(settingsPrefix, "credentialsDir");
        this.settingsAccountName = settings.getProperty(settingsPrefix, "name");

        this.applicationName = settings.getApplicationName();
        this.scopes = Arrays.asList(CalendarScopes.CALENDAR);
        this.dataStoreDir = new java.io.File(this.settingsCredentialsDir);

        httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        fileDataStoreFactory = new FileDataStoreFactory(dataStoreDir);

        service = getCalendarService();
    }

    @Loggable(Loggable.DEBUG)
    public com.google.api.services.calendar.Calendar getService()
    {
        return service;
    }

    public void setService(com.google.api.services.calendar.Calendar service)
    {
        this.service = service;
    }

    /**
     * Creates an authorized Credential object.
     *
     * @return an authorized Credential object.
     * @throws IOException
     */
    @LogExceptions
    @Loggable(Loggable.DEBUG)
    public Credential authorize() throws IOException, Exception
    {
        String message = String.format("Authorizing user to %s", this.settingsAccountName);
        logger.info(message);
        System.out.println(message);

        // Load client secrets.
        File f = new File(settings.getClientSecret());
        FileInputStream in = new FileInputStream(f);
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setDataStoreFactory(fileDataStoreFactory)
                .setAccessType("offline").build();
        Credential credential = new AuthorizationCodeInstalledApp(flow,
                new LocalServerReceiver()).authorize("user");

        message = "Authorization succeeded. Credentials saved to " + dataStoreDir.getAbsolutePath();
        logger.info(message);
        System.out.println(message);

        return credential;
    }

    /**
     * Build and return an authorized Calendar client service.
     *
     * @return an authorized Calendar client service
     * @throws IOException
     */
    public com.google.api.services.calendar.Calendar getCalendarService()
            throws IOException, Exception
    {
        Credential credential = authorize();
        return new com.google.api.services.calendar.Calendar.Builder(
                httpTransport, JSON_FACTORY, credential).setApplicationName(
                applicationName).build();
    }

    public List<CalendarListEntry> getCalendarList() throws IOException
    {
        String pageToken = null;
        List<CalendarListEntry> items = new ArrayList<CalendarListEntry>();

        do
        {
            CalendarList calendarList = this.getService().calendarList().list().setPageToken(pageToken).execute();
            items.addAll(calendarList.getItems());
            pageToken = calendarList.getNextPageToken();
        } while (pageToken != null);

        return items;
    }

    public void logInfoAboutAccount()
    {
        logCalendarList();
        logCalendarColors();
    }

    private void logCalendarList()
    {
        try {
            logger.debug(String.format("List of calendars for %s", settingsAccountName));
            for (CalendarListEntry ce : this.getCalendarList())
            {
                logger.debug(String.format("  - Summary: %s, id: %s", ce.getSummary(), ce.getId()));
            }
        }
        catch (Exception e)
        {
            logger.warn("Unable to get calendar list", e);
        }
    }

    private void logCalendarColors()
    {
        try
        {
            Colors colors = this.getService().colors().get().execute();

            // Print available calendar list entry colors
            logger.debug(String.format("List of calendar colors for %s", settingsAccountName));
            for (Map.Entry<String, ColorDefinition> color : colors.getCalendar().entrySet())
            {
                logger.debug("  - ColorId : " + color.getKey());
                logger.debug("    Background: " + color.getValue().getBackground());
                logger.debug("    Foreground: " + color.getValue().getForeground());
            }
        }
        catch (IOException e)
        {
            logger.error("Error in logCalendarColors", e);
        }
    }

    public String getSettingsAccountName()
    {
        return settingsAccountName;
    }

}