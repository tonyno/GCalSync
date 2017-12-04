# GCalSync - Google Calendar one-way synchronization

> Disclaimer: This is my very first application written in Java, so don't be too strict during your review of my codes ;-).

The aim of this small tool is one way synchronization from one Google Calendar to another. 
Reason for implementing this tool is security policy what disallows sharing whole calendars to other collegues.

## How it works

Application uses oAuth2 authorization to access both of your calendars - the source one, where the data are loaded from 
and destination one, where the data are stored into.

The synchronization is always one way direction = from source calendar to destination.

The tool verifies during synchronization whether the event does or does not exist in
the destination calendar. If yes, then the event is updated if it was changed or deleted
if it was deleted in source calendar.

To remember where the synchronization ended last time, tool saves special file containing `lastSyncToken`
field, what is input to the Google Calendar API for the next search for new events.

## Configuration file

Tool requires existence of configuration file located at `conf/config.properties`.

Content of the configuration file consists from 3 parts:
- Whole synchronization scope
- Accounts definition
- Synchronization definition

#### Config - Whole synchronization scope

Begining of the config file contains:

```
client.secret = conf/client_secret.json
client.applicationName = Google Calendar Sync
```

Description:
* `client.secret` - absolute/relative path to the client_secret.json you have created 
for your application in [Google Developers Console](https://console.developers.google.com/projectcreate).
In the Console you need to allow `Google Calendar API` and generate credentials for `Other` type of application.
* `client.applicationName` - name of application

#### Config - Accounts definition

Bellow the whole synchronization scope is placed section for definition of the accounts.

```
account.1.name = Account myspecialaccount@gmail.com
account.1.credentialsDir = conf/settings-cs
account.2.name = Account myanotheraccount@gmail.com
account.2.credentialsDir = conf/settings-personal
```

You may define various number of accounts used for synchronization. All accounts have identification name/id
what is specified next to `account.` prefix. For all accounts you need to specify two fields:
* `name` - just name of the account, used in the logs
* `credentialsDir` - directory, where `Google Calendar API` stores the credential informations to your account after
succeeding the oAuth2 authorization. The directory must exist in advance.

#### Config - Synchronization definition

Last section defines the synchronization requests itself. You may specify more synchronization requests under `sync.N` 
prefix, where `N` goes from 1 to number of requests you need.

This part of config looks like:

```
sync.1.source = 1
sync.1.source.calendar = primary
sync.1.source.lastSyncTokenFile = conf/settings-cs/lastSyncToken.properties

# ID of the target account
sync.1.destination = 2
sync.1.destination.calendar = 4o3i13vmi3xt0fl5ldtrcculck@group.calendar.google.com
#sync.1.destination.color = 24

sync.1.dryRun = FALSE
sync.1.maximumEvents = 1
#sync.1.summary.appendix = (Synchro)
#sync.1.description.appendix = \n\n\n%%%%%SYNCHRONIZATION%%%%%
sync.1.description.skipSynchroPattern = %%MY-SYNCHRONIZATION%%
```

Description of fields:
* `sync.N.source` - ID or name of the account defined in previous section of configuration file,
see section _Config - Accounts definition_
* `sync.N.source.calendar` - ID of the calendar where the events are loaded from. Primary user's calendar
has id `primary`.
* `sync.N.source.lastSyncTokenFile` - path to the file, where the last sync token is stored
* `sync.N.destination` - ID of the destination account where all the events are stored into
* `sync.N.destination.calendar` - ID of the destination calendar
* `sync.N.destination.color` (_optional_) - ID of the color for new created events
* `sync.N.dryRun` (_optional_) - allows specifying dry run without modification in target calendar
* `sync.N.maximumEvents` (_optional_) - allows specifying maximum of events being synchronized (for debuging purposes)
* `sync.N.summary.appendix` (_optional_) - this text is added to the end of the summary of all created events
* `sync.N.description.appendix` (_optional_) - this text is added to the end of description of all created events
* `sync.N.description.skipSynchroPattern` (_optional_) - if this text appears in the description of source event, then the event is not synchronized.
This is useful for example if you synchronize calendars A->D, B->D, D->E, but you want to synchronize in D->E only events from A, not B. 


## License

See [LICENSE.md](LICENSE.md)