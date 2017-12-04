package net.czechit.gcalsync;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.Events;
import org.apache.commons.codec.binary.Base32;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OneWaySync
{
    public enum Operation {UNKNOWN, INSERT, DELETE, UPDATE};

    private final static Logger logger = Logger.getLogger(OneWaySync.class);

    private CalendarSettings settings;

    /** Prefix for properties key for synchronization, value like: sync.1 */
    private String settingsPrefix;

    /** Prefix for properties key of the source account where we load the data from */
    private String sourcePrefix;

    /** Prefix for properties key of the destination account where we store the data to */
    private String destinationPrefix;

    /** ID of the source calendar */
    private String sourceCalendarName;

    /** ID of the destination calendar */
    private String destinationCalendarName;

    /** Color of the event in destination calendar */
    private String destinationEventColor;

    /** Optional - Dry run without changing destination calendar? */
    private boolean dryRun;

    /** Optional - appendix what is added to event name */
    private String summaryAppendix;

    /** Optional - appendix what is added to event description */
    private String descriptionAppendix;

    /** Optional - if this text appears in description of the source event, then the event is not synchronized */
    private String skipSynchroDescriptionPattern;

    /** Runtime settings for the source calendar */
    private RuntimeSettings sourceRuntimeSettings;

    private CalendarConnection source;
    private CalendarConnection destination;

    com.google.api.services.calendar.Calendar sourceCalendar;
    com.google.api.services.calendar.Calendar destinationCalendar;


    public OneWaySync(CalendarSettings settings, String prefix) throws Throwable
    {
        this.settings = settings;
        this.settingsPrefix = prefix;

        sourcePrefix = String.format("account.%s", settings.getProperty(prefix, "source"));
        destinationPrefix = String.format("account.%s", settings.getProperty(prefix, "destination"));

        source = new CalendarConnection(settings, sourcePrefix);
        source.logInfoAboutAccount();
        sourceCalendar = source.getService();

        destination = new CalendarConnection(settings, destinationPrefix);
        destination.logInfoAboutAccount();
        destinationCalendar = destination.getService();

        sourceCalendarName = settings.getProperty(prefix, "source.calendar");
        destinationCalendarName = settings.getProperty(prefix, "destination.calendar");

        destinationEventColor = settings.getNonmandatoryProperty(prefix, "color");
        sourceRuntimeSettings = new RuntimeSettings(settings.getProperty(prefix, "source.lastSyncTokenFile"));
        dryRun = settings.getNonmandatoryProperty(prefix, "dryRun").equalsIgnoreCase("TRUE");

        summaryAppendix = settings.getNonmandatoryProperty(prefix, "summary.appendix");
        if (!summaryAppendix.equals("")) summaryAppendix = " " + summaryAppendix;
        descriptionAppendix = settings.getNonmandatoryProperty(prefix, "description.appendix").replaceAll("\\\\n", "\n");

        skipSynchroDescriptionPattern = settings.getNonmandatoryProperty(prefix, "description.skipSynchroPattern");
    }

    public void sync() throws IOException
    {
        String syncToken = sourceRuntimeSettings.getLastSyncToken();
        logger.info(String.format("Starting synchronization events from %s (calendar %s) to %s (calendar %s), lastSyncToken = %s",
                source.getSettingsAccountName(), sourceCalendarName,
                destination.getSettingsAccountName(), destinationCalendarName,
                syncToken));
        Calendar.Events.List request = sourceCalendar.events().list(sourceCalendarName);
        if (null == syncToken || "".equals(syncToken))
        {
            DateTime now = new DateTime(System.currentTimeMillis());
            request.setTimeMin(now).setMaxResults(1);
        } else {
            request.setSyncToken(syncToken);
        }

        int numberOfEvents = 0;
        String pageToken = null;
        Events events = null;
        do {
            request.setPageToken(pageToken);

            try {
                events = request.execute();
            }
            catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 410) {
                    // A 410 status code, "Gone", indicates that the sync token
                    // is invalid.
                    logger.error("Invalid sync token, restarting again without token", e);
                    sourceRuntimeSettings.setLastSyncToken("");
                    sync();
                } else {
                    throw e;
                }
            }

            List<Event> items = events.getItems();
            if (items.size() > 0)
            {
                for (Event event : items) {
                    try {
                        numberOfEvents++;
                        syncEvent(event, destinationCalendar);
                        Thread.sleep(5*1000);
                        // return;
                    }
                    catch (Exception e)
                    {
                        logger.error("Problem during syncing " + event.getId() + " - " + event.getSummary(), e);
                        // return; // TODO
                    }
                }
            }

            pageToken = events.getNextPageToken();
        } while (pageToken != null);

        syncToken = events.getNextSyncToken();
        logger.info(String.format("Synchronization done, number od events synchronized = %d, next syncToken = ", numberOfEvents, syncToken));
        sourceRuntimeSettings.setLastSyncToken(syncToken);
    }

    private void syncEvent(Event event, Calendar targetCalendar) throws IOException
    {
        String sourceId = fixId(event.getId());
        Operation operation = Operation.UNKNOWN;

        String logMessage;
        if (event.getStatus().equals("cancelled")) // request to delete event
        {
            operation = Operation.DELETE;
            logMessage = String.format("Deleting event id=%s", event.getId());
        } else {
            logMessage = String.format("Syncing event summary=%s, id=%s, start=%s, status=%s",
                    event.getSummary(), event.getId(), event.getStart().toString(), event.getStatus());
        }

        logger.debug(String.format("Following event going to be synced, operation: %s, event: %s", logMessage, event.toPrettyString()));

        // We try to find corresponding event in destination calendar based on event id (event.getId())
        Event targetEvent = null;
        try {
            targetEvent = targetCalendar.events().get(destinationCalendarName, sourceId).execute();

            // No exception raised = corresponding event in destination calendar exists
            logger.debug(String.format("   -> found in target calendar under id=%s, summary=%s, start=%s, status=%s",
                    sourceId, targetEvent.getSummary(), targetEvent.getStart().toString(), targetEvent.getStatus()));

            if (operation == Operation.DELETE && targetEvent.getStatus().equalsIgnoreCase("cancelled"))
            {
                logger.debug("   -> event is in target calendar marked as cancelled, so synchronization is skipped");
                return;
            }

            if (operation == Operation.UNKNOWN) { // if it is not DELETE operation already
                operation = Operation.UPDATE;
            }
        }
        catch (GoogleJsonResponseException e)
        {
            if (e.getStatusCode() == 404) // Corresponding event in destination calendar doesn't exist (HTTP code 404)
            {
                if (operation == Operation.DELETE) // We are asked to delete event, but it doesn't exist in destination calendar, so we just ignore the request
                {
                    logger.warn("   -> not found in target calendar and requested to be deleted, so ignoring");
                    return;
                }

                targetEvent = new Event();
                targetEvent.setId(sourceId);

                logger.debug("   -> not found, creating new event");
                operation = Operation.INSERT;
            } else {
                logger.error("    -> other unknown GoogleJsonResponseException error", e);
                throw e;
            }
        }

        targetEvent.setSummary(Objects.toString(event.getSummary(), "") + summaryAppendix);
        targetEvent.setDescription(Objects.toString(event.getDescription(), "") + descriptionAppendix);
        targetEvent.setLocation(event.getLocation());
        targetEvent.setStart(event.getStart());
        targetEvent.setEnd(event.getEnd());
        targetEvent.setReminders(event.getReminders());
        targetEvent.setStatus("confirmed");
        targetEvent.setAttendees(event.getAttendees());

        targetEvent.setRecurrence(event.getRecurrence());
        targetEvent.setRecurringEventId(event.getRecurringEventId());
        targetEvent.setSequence(event.getSequence());
        // https://stackoverflow.com/questions/9691665/google-calendar-api-can-only-update-event-once
        // https://stackoverflow.com/questions/8574088/google-calendar-api-v3-re-update-issue
        if (! destinationEventColor.equals(""))
            targetEvent.setColorId(destinationEventColor);

        logger.info(String.format("Performing operation %s with following target event, id = %s, content = %s", operation, targetEvent.getId(), targetEvent.toPrettyString()));

        if (skipSynchroDescriptionPattern != null && !skipSynchroDescriptionPattern.equals("") &&
                (event != null) && (event.getDescription() != null) && event.getDescription().contains(skipSynchroDescriptionPattern))
        {
            logger.debug(String.format("Source event %s contains skipSynchroDescriptionPattern text, so it will not be synchronized to destination calendar", event.getSummary()));
            return;
        }

        if (dryRun) {
            logger.debug("Dry run set, so no modification will be performed. Exitting syncEvent routine.");
            return;
        }

        switch (operation)
        {
            case INSERT:
                targetCalendar.events().insert(destinationCalendarName, targetEvent).execute();
                logger.debug("Operation INSERT finished");
                break;
            case UPDATE:
                // https://developers.google.com/google-apps/calendar/v3/reference/events/update
                targetCalendar.events().update(destinationCalendarName, sourceId, targetEvent).execute();
                logger.debug("Operation UPDATE finished");
                break;
            case DELETE:
                targetCalendar.events().delete(destinationCalendarName, sourceId).execute();
                logger.debug("Operation DELETE finished");
                break;
            default:
                logger.error("Unknown operation - " + operation);
        }

    }


    /*
     * Removes _ and all other not supported characters in the event id.
     * It is typically neccessary for repeating events, where the ID looks like rva3c7gdfup1gp6hb408hkeu4c_R20171018T130000
     * Any other letteran than a-v and 0-9 cannot be saved to synced new event, so we must remove it.
     *    (characters allowed in the ID are those used in base32hex encoding, i.e. lowercase letters a-v and digits 0-9, see section 3.1.2 in RFC2938)
     *    See description for https://developers.google.com/google-apps/calendar/v3/reference/events/insert
     *
     * Based on testing it seems it is reasonable not to change the ID if not neccessary, then Google consider it just as copy of the event and doesn't
     * revalidate the meeting room reservations etc.
     */
    public static String fixId(String id)
    {
        String newValue = id.replaceAll("[^a-v0-9]+", "0");

        // I dont use following option because all IDs would differ between source and destination and it will be hard for debuging
        // Base32 base32 = new Base32(true);
        // String newValue = base32.encodeAsString(id.getBytes()).toLowerCase().replace('=','0');
        logger.debug(String.format("Fixing ID of event from %s to %s", id, newValue));
        return newValue;
    }

    private static List<EventAttendee> filtersAttendees(List<EventAttendee> attendees)
    {
        if (attendees == null)
            return null;

        List<EventAttendee> newAttendees = new ArrayList<EventAttendee>();
        newAttendees.addAll(attendees);
        for (EventAttendee e : newAttendees)
        {
            String displayName = e.getDisplayName();
            if (displayName == null || displayName.equals(""))
                e.setDisplayName(e.getEmail());
            e.setEmail("nomail@gmail.com");
        }
        return newAttendees;
    } // csas.cz_70726168612d616e74616c61737461736b612d726970@resource.calendar.google.com

    /**
     * Closes all connections and saves all data
     */
    public void close()
    {
        sourceRuntimeSettings.save();
        sourceRuntimeSettings.close();
    }
}
