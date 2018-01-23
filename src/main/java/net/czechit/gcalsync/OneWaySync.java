package net.czechit.gcalsync;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.Events;
import net.czechit.gcalsync.exceptions.RecurringEventNotFoundException;
import org.apache.commons.codec.binary.Base32;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneWaySync
{
    public enum Operation {UNKNOWN, INSERT, DELETE, UPDATE};

    private final static Logger logger = LoggerFactory.getLogger(OneWaySync.class);

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

    /** Optional - sleepTime (in msec) used between events syncing */
    private int sleepTimeMsec = 1000;

    /** Optional - maximum events to be synchronized, for debuging purposes */
    private int maximumEvents;

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

        String sleepTimeMSecStr = settings.getNonmandatoryGlobalProperty("sleepTimeMSec");
        if (!sleepTimeMSecStr.isEmpty()) {
            sleepTimeMsec = Integer.parseInt(sleepTimeMSecStr);
        }

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

        destinationEventColor = settings.getNonmandatoryProperty(prefix, "destination.color");
        sourceRuntimeSettings = new RuntimeSettings(settings.getProperty(prefix, "source.lastSyncTokenFile"));
        dryRun = settings.getNonmandatoryProperty(prefix, "dryRun").equalsIgnoreCase("TRUE");

        String maxEvents =  settings.getNonmandatoryProperty(prefix, "maximumEvents");
        if (maxEvents != null && !maxEvents.equals(""))
            maximumEvents = Integer.parseInt(maxEvents);
        else
            maximumEvents = 0;

        summaryAppendix = settings.getNonmandatoryProperty(prefix, "summary.appendix");
        if (!summaryAppendix.equals("")) summaryAppendix = " " + summaryAppendix;
        descriptionAppendix = settings.getNonmandatoryProperty(prefix, "description.appendix").replaceAll("\\\\n", "\n");

        skipSynchroDescriptionPattern = settings.getNonmandatoryProperty(prefix, "description.skipSynchroPattern");
    }

    /**
     * Main synchronization routine - takes configurations loaded in class constructor, takes lastSyncToken and perform
     * the one way synchronization.
     * @throws IOException
     */
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

        tokenLoop:
        do {
            request.setPageToken(pageToken);

            try {
                events = request.execute();
            }
            catch (GoogleJsonResponseException e) {
                logger.error(String.format("Exception during request execution, request = %s", request.toString()), e);
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
                        if (maximumEvents == 0 || numberOfEvents < maximumEvents)
                        {
                            numberOfEvents++;
                            syncEvent(event, destinationCalendar);
                            Thread.sleep(sleepTimeMsec);
                        }
                    }
                    catch (Exception e)
                    {
                        logger.error("Problem during syncing " + event.getId() + " - " + event.getSummary(), e);
                    }
                }
            }

            pageToken = events.getNextPageToken();
        } while (pageToken != null);

        syncToken = events.getNextSyncToken(); // be careful, if the loading of events is cancelled in the middle, then the syncToken is null, because if needs to be loaded from the begining
        logger.info(String.format("Synchronization done, number od events synchronized = %d, next syncToken = %s", numberOfEvents, syncToken));
        sourceRuntimeSettings.setLastSyncToken(syncToken);
    }


    /**
     * Sync one event to targetCalendar
     * @param event one event being synchronized to targetCalendar
     * @param targetCalendar calendar where the new event will be placed/updated to
     * @throws IOException
     */
    private void syncEvent(Event event, Calendar targetCalendar) throws IOException
    {
        String debugAppendix = ""; // " (DEBUG)";
        String sourceIdUnfixed = event.getId();
        String sourceId = fixId(sourceIdUnfixed);
        /*if (!( sourceId.equals("0elin6eir68sj8cpl60q3gn9qbcq38c9p64qjic1g6sp3id1h74sj6n9qbcsjgchj6grjgd9m60oj8d1p6cqj0n8") ||
                sourceId.equals("0elin6eir68sj8cpl6...0q3gn9qbcq38c9p64qjic1g6sq3cchg6crjgn9qbcsjgchj6grjgd9m60pj0dhp60s3gn8")))
            return;*/

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
        Event targetEvent = findEvent(event, targetCalendar);

        if (targetEvent != null) // the event is found in the target calendar
        {
            if (operation == Operation.DELETE && targetEvent.getStatus().equalsIgnoreCase("cancelled"))
            {
                logger.debug("   -> event is in target calendar marked as cancelled, so synchronization is skipped");
                return;
            }

            if (operation == Operation.UNKNOWN) { // if it is not DELETE operation already
                operation = Operation.UPDATE;
            }
        } else { // event is not found in targetCalendar
            if (operation == Operation.DELETE) // We are asked to delete event, but it doesn't exist in destination calendar, so we just ignore the request
            {
                logger.warn("   -> not found in target calendar and requested to be deleted, so ignoring");
                return;
            }

            targetEvent = new Event();
            targetEvent.setId(sourceId);

            logger.debug("   -> not found, creating new event");
            operation = Operation.INSERT;
        }

        // Load data from event to targetEvent
        syncEvent(event, targetEvent, debugAppendix);

        logger.info(String.format("Performing operation %s with following target event, id = %s, content = %s", operation, targetEvent.getId(), targetEvent.toPrettyString()));


        // If description of the event contains special text saying "do not synchronize"....
        if (skipSynchroDescriptionPattern != null && !skipSynchroDescriptionPattern.equals("") &&
                (event != null) && (event.getDescription() != null) && event.getDescription().contains(skipSynchroDescriptionPattern))
        {
            logger.debug(String.format("Source event %s contains skipSynchroDescriptionPattern text, so it will not be synchronized to destination calendar", event.getSummary()));
            return;
        }

        // If it is just dry text - in the last moment before changing the data exit the routine
        if (dryRun) {
            logger.debug("Dry run set, so no modification will be performed. Exiting syncEvent routine.");
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
                targetCalendar.events().update(destinationCalendarName, targetEvent.getId(), targetEvent).execute();
                logger.debug("Operation UPDATE finished");
                break;
            case DELETE:
                targetCalendar.events().delete(destinationCalendarName, targetEvent.getId()).execute();
                logger.debug("Operation DELETE finished");
                break;
            default:
                logger.error("Unknown operation - " + operation);
        }
    }


    /**
     * Finds event using event.id in targetCalendar and returns that event as return value.
     * It supports both one single event and also recurring events
     * @param event to be searched in targetCalendar based on event.getId()
     * @param targetCalendar calendar where the event is being searched
     * @return null if event is not found or the particular Event what was found
     * @throws IOException
     */
    private Event findEvent(Event event, Calendar targetCalendar) throws IOException
    {
        String sourceIdUnfixed = event.getId();
        String sourceId = fixId(sourceIdUnfixed);

        // We try to find corresponding event in destination calendar based on event id (event.getId())
        Event targetEvent = null;
        try {
            if (event.getRecurringEventId() != null && !event.getRecurringEventId().isEmpty()) // Is it recurring event?
            {
                targetEvent = findRecurringEvent(event, targetCalendar);
            } else
            {
                targetEvent = targetCalendar.events().get(destinationCalendarName, sourceId).execute();
            }

            // No exception raised = corresponding event in destination calendar exists
            if (targetEvent == null)
            {
                logger.debug(String.format("   -> event %s not found in target calendar", sourceId));
            } else
            {
                logger.debug(String.format("   -> found in target calendar under id=%s, summary=%s, start=%s, status=%s, content=%s",
                        targetEvent.getId(), targetEvent.getSummary(), targetEvent.getStart().toString(), targetEvent.getStatus(), targetEvent.toPrettyString()));
            }

            /*if (operation == Operation.DELETE && targetEvent.getStatus().equalsIgnoreCase("cancelled"))
            {
                logger.debug("   -> event is in target calendar marked as cancelled, so synchronization is skipped");
                return;
            }

            if (operation == Operation.UNKNOWN) { // if it is not DELETE operation already
                operation = Operation.UPDATE;
            }*/
        }
        catch (GoogleJsonResponseException e)
        {
            if (e.getStatusCode() == 404) // Corresponding event in destination calendar doesn't exist (HTTP code 404)
            {
                logger.debug(String.format("   -> event %s not found in target calendar (404 code)", sourceId));
                targetEvent = null;
            } else {
                logger.error("    -> other unknown GoogleJsonResponseException error", e);
                throw e;
            }
        }

        return targetEvent; // return found targetEvent
    }

    /**
     * Routine for searching recurring event in targetCalendar (used from findEvent method)
     * @param event
     * @param targetCalendar
     * @return
     * @throws IOException
     */
    private Event findRecurringEvent(Event event, Calendar targetCalendar) throws IOException
    {
        String sourceIdUnfixed = event.getId();
        String sourceId = fixId(sourceIdUnfixed);

        // https://developers.google.com/google-apps/calendar/recurringevents
        List<Event> recurringEvents = targetCalendar.events().instances(destinationCalendarName, event.getRecurringEventId()).setMaxResults(2000).execute().getItems();

        logger.debug(String.format("Number of recurring events: %d", recurringEvents.size()));
        for (Event recEvent : recurringEvents)
        {
            if (recEvent.getId().equals(sourceIdUnfixed))
            {
                logger.debug(String.format("This is the correct recurring event to be updated - id %s " +
                        "from %s to %s", recEvent.getId(), recEvent.getStart(), recEvent.getEnd()));
                return recEvent; // event found
            }
        }
        return null; // if event not found
    }


    /**
     * Copies attributes from source event "event" to target event "targetEvent"
     * @param event source event
     * @param targetEvent target event
     * @param debugAppendix optional appendix being put in the end of summary of all created events (for debuging purposes)
     */
    private void syncEvent(Event event, Event targetEvent, String debugAppendix)
    {
        targetEvent.setSummary(Objects.toString(event.getSummary(), "") + summaryAppendix + debugAppendix);
        targetEvent.setDescription(Objects.toString(event.getDescription(), "") + attendeesToDescription(event.getAttendees()) + descriptionAppendix);
        targetEvent.setLocation(event.getLocation());
        targetEvent.setStart(event.getStart());
        targetEvent.setEnd(event.getEnd());
        targetEvent.setReminders(event.getReminders());
        targetEvent.setStatus("confirmed");


        targetEvent.setRecurrence(event.getRecurrence());
        targetEvent.setRecurringEventId(event.getRecurringEventId());

        // As event sequence number we will take higher number from original event and event being synchronized.
        // This number needs to increase (or remain same) on all synchronization requests.
        Integer newSequence = Math.max(nvl(targetEvent.getSequence(), 0), nvl(event.getSequence(), 0));
        targetEvent.setSequence(newSequence);
        // https://stackoverflow.com/questions/9691665/google-calendar-api-can-only-update-event-once
        // https://stackoverflow.com/questions/8574088/google-calendar-api-v3-re-update-issue

        if (! destinationEventColor.equals(""))
            targetEvent.setColorId(destinationEventColor);

        //targetEvent.setAttendees(filtersAttendees(event.getAttendees())); // do not put attendees to the new synchronized events,
        // otherwise the invited persons will see duplicate invitations from both of your calendars in their calendar.

        //targetEvent.setICalUID(event.getICalUID()); // never ever put this to new issues, it causes Invalid ID exceptions
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


    /**
     * Converts attendees list into description
     * @param attendees
     * @return
     */
    private static String attendeesToDescription(List<EventAttendee> attendees)
    {
        if (attendees == null)
            return "";

        String retVal = "\n\n==============\nATTENDEES:\n";
        for (EventAttendee e : attendees)
        {
            retVal += String.format("%s (%s) - status: %s%s\n",
                    (e.getDisplayName() != null ? e.getDisplayName() : "-"),
                    (e.getEmail().contains("resource.calendar.google.com") ? "--" : e.getEmail()),
                    e.getResponseStatus(),
                    (e.getComment() != null && !e.getComment().equals("")) ? (" (" + e.getComment() + ")") : "");
        }
        return retVal;
    }

    /**
     * Closes all connections and saves all data
     */
    public void close()
    {
        sourceRuntimeSettings.save();
        sourceRuntimeSettings.close();
    }

    public <T> T nvl(T arg0, T arg1) {
        return (arg0 == null) ? arg1 : arg0;
    }

}
