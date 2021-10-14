# About

Observer helps to manage Email archives.
The interface allows to view Emails imported from MBOX archives.  The functionality is highly customizable because of use the lsFusion platform.
Data from MBOX archives uploaded by user are being automatically imported into Database for this purpose.

## Observer Functionality

* Read list of MBOX archives uploaded in certain folder on the server,
* Import data from MBOX archive in a background task,
* Emails viewer with extended filtration features.

## lsFusion Platform resources

* https://lsfusion.org
* [Slack Discussion](https://slack.lsfusion.org/)

---

# HowTo

## Working Forms

* **Folders** - main form to work with, all target functionality is there,
* **Parameters** - application settings (in **Master data** menu),
* **Scheduler** - background data importing (in **Administration** menu),
* **Account** - user authentication settings.

## Application settings

* **Absolute path to local storage of MBOX files** - path to archives storage on the server' filesystem, must be created and specified by server Administrator;
* **Storage for MBOX files** - subfolders in the storage:
   + **manual** - files uploaded using SSH connection,
   + **auto** - files uploaded using GUI,
   + **import** - files, identified by application for data importing,
   + **arch** - processed files;
* **MBOX archives at import** - number of uploaded MBOX archives in importing queue; 
* **Playlist is running** - name of currently running playlist;
* **Import process is busy** - flag of running importing from an MBOX archive.

## Workflow on the Folders form

* **Folders** - list of imported Email folders;
* Buttons **Add**, **Edit**, **Delete** - run named Editor for selected folder.

## Folder Editor

* **Name** - set custom name for the Folder
* **in** - tick if data from the MBOX archive should be used in the current folder
* **File Name** - file name of the MBOX archive on the server
* **Delete file** - remove the MBOX archive from the server
* Button **Check for new manually uploaded files** - screen the manual folder on the server and detect new files there
* Buttons **Save**, **OK**, **Cancel**, **Close** - save changes in the database.
   *Important: without pressing one of these buttons no changes will be stored in case of closing window or disconnect from the application.*

## Data Import

The data import runs in Scheduler.
It checks whether any newly uploaded MBOX archives are available.
Get next MBOX archive from the conversion queue (if any) and runs importing job, if there is no active importing already. 

The Scheduler need to be configured by the server admin:

1. Go **Administration** -> **Scheduler** -> **Tasks**.
2. **+Add** new Scheduler task.
3. Type desired **Name**, select **Scheduler start type** *From finish previous*, set **Repeat each** *60* **seconds**, select current date and time as **Start date**.
4. **+Add** new Scheduled task row.
5. Find **Action** *Observer.importNextFile[]* aka *Import from next MBOX archive*.
6. Tick **Active** flags for the row and the Task itself.
7. **Save** your changes.
8. Press button **Perform a task**.

---

