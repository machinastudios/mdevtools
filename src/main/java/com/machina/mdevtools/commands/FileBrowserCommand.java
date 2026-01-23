package com.machina.mdevtools.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.machina.mdevtools.Main;
import com.machina.mdevtools.commands.filebrowser.FileBrowserIconRegistry;
import com.machina.minterfacebuilder.factory.ComponentFactory;
import com.machina.minterfacebuilder.helpers.Color;
import com.machina.minterfacebuilder.helpers.LayoutMode;
import com.machina.minterfacebuilder.util.customui.ComponentBuilder;
import com.machina.minterfacebuilder.util.customui.components.HTitle;
import com.machina.minterfacebuilder.util.customui.components.base.Button;
import com.machina.minterfacebuilder.util.customui.components.base.Group;
import com.machina.minterfacebuilder.util.customui.components.base.Image;
import com.machina.minterfacebuilder.util.customui.components.base.Label;
import com.machina.minterfacebuilder.util.customui.components.custom.DecoratedDialogPage;
import com.machina.shared.factory.ModLogger;
import com.machina.shared.model.SuperPluginCommandHandler;

public class FileBrowserCommand extends SuperPluginCommandHandler {
    public FileBrowserCommand() {
        super("filebrowser", "Browse and edit server files");
    }

    @Override
    public void executeSync(@Nonnull CommandContext context) {
        // Create a new file browser page
        var fileBrowserPage = new FileBrowserPage();

        // Open the file browser page
        fileBrowserPage.send(context.senderAsPlayerRef());
    }
}

class FileBrowserPage extends DecoratedDialogPage {
    /**
     * The root path.
     */
    private final Path rootPath = Path.of(".");

    /**
     * The current path.
     */
    private Path currentPath = rootPath;

    /**
     * The file entries.
     */
    private final List<FileBrowserFileEntry> fileEntries = new ArrayList<>();

    /**
     * The current file entry.
     */
    private FileBrowserFileEntry currentFile = null;

    /**
     * The file list group.
     */
    private Group fileListGroup;

    public FileBrowserPage() {
        super();
    }

    protected void constructDialog() {
        // Create the file list group
        fileListGroup = (Group) ComponentFactory.create(Group.class)
            .setId("FileList")
            .setProperty("LayoutMode", LayoutMode.TOP)
            .setProperty("Anchor", Map.of("Width", FileBrowserLine.getTotalWidth()));

        // Create the file entries
        listCurrentPathFiles();

        getDialog().setTitle(
            ComponentFactory.create(HTitle.class).setText("File Browser")
        );

        // Add the file list group to the page
        updateListUI();

        // Add the file list group to the dialog
        getDialog().setContent(fileListGroup);
    }

    /**
     * Set the current path.
     * @param currentPath The current path.
     * @return The file browser page.
     */
    protected FileBrowserPage setCurrentPath(Path currentPath) {
        this.currentPath = currentPath;
        return this;
    }

    /**
     * Get the current path.
     * @return The current path.
     */
    protected Path getCurrentPath() {
        return currentPath;
    }

    /**
     * Get the root path.
     * @return The root path.
     */
    protected Path getRootPath() {
        return rootPath;
    }

    /**
     * Get a file entry from an id.
     * @param id The id.
     * @return The file entry.
     */
    protected FileBrowserFileEntry getEntryFromId(String id) {
        // Get the file entry index from the clicked button id
        int fileEntryIndex = Integer.parseInt(id.replace("Edit-", ""));

        // Get the file entry
        FileBrowserFileEntry fileEntry = fileEntries.get(fileEntryIndex);

        return fileEntry;
    }

     /**
     * List the files in the current path.
     */
    protected void listCurrentPathFiles() {
        // Clear the file entries
        fileEntries.clear();

        // List the files in the current path
        for (File file : currentPath.toFile().listFiles()) {
            fileEntries.add(new FileBrowserFileEntry(file.toPath(), fileEntries.size()));
        }
    }

    /**
     * Set the current file.
     * @param currentFile The current file.
     * @return The file browser page.
     */
    protected FileBrowserPage setCurrentFile(FileBrowserFileEntry currentFile) {
        this.currentFile = currentFile;
        return this;
    }

    /**
     * Get the file entries.
     * @return The file entries.
     */
    protected List<FileBrowserFileEntry> getFileEntries() {
        return fileEntries;
    }

    /**
     * Update the list of file entries.
     */
    protected void updateListUI() {
        // Clear the file list group
        fileListGroup.clearChildren();

        // Get the file list
        var fileList = getFileEntries();

        // The index of the file entry
        int index = 0;

        // If not in the root path, add a button to go back to the root path
        if (!getCurrentPath().equals(getRootPath())) {
            fileList.add(0, new FileBrowserFileEntry(getRootPath(), index++, ".."));
        }

        // Add the header line to the file list group
        fileListGroup.appendChild(new FileBrowserLineLabel());

        // List the files in the current path
        for (FileBrowserFileEntry fileEntry : getFileEntries()) {
            // Set the index
            fileEntry.setIndex(index++);

            // Add the file entry to the file list group
            fileListGroup.appendChild(fileEntry);

            // If it's a directory
            if (fileEntry.getFile().isDirectory()) {
                // Add an event listener for the directory buttons
                addEventListener(EventType.CLICK, "#FileEntry" + fileEntry.getIndex(), (event) -> {
                    // Set the current path
                    setCurrentPath(fileEntry.getFile().toPath());

                    // List the files in the new path
                    listCurrentPathFiles();

                    // Send them to the player
                    send();
                });
            } else
            // If is editable
            if (fileEntry.isEditable()) {
                // Add an event listener for the edit buttons
                addEventListener(EventType.CLICK, "#FileEntry" + fileEntry.getIndex() + " #EditButton", (event) -> {
                    // Set the current file
                    setCurrentFile(fileEntry);
                });
            }
        }
    }
}

class FileBrowserFileEntry extends FileBrowserLine {
    /**
     * The logger.
     */
    private final ModLogger logger = ModLogger.forMod(Main.INSTANCE, "FileBrowserFileEntry");

    /**
     * The file path.
     */
    private final Path filePath;

    /**
     * The file.
     */
    private final File file;

    /**
     * The index of the file entry.
     */
    private int index;

    /**
     * The file type.
     */
    private String fileType;

    /**
     * The file name to be overridden.
     */
    private String fileName;

    public FileBrowserFileEntry(Path filePath, int index) {
        super();

        this.filePath = filePath;
        this.file = filePath.toFile();
        this.index = index;

        if (file.isDirectory()) {
            this.fileType = "directory";
        } else {
            // Get the file type
            try {
                this.fileType = Files.probeContentType(filePath);
            } catch (IOException e) {
                // If the file type is not known, set it to null
                logger.error("Failed to get the file type of " + filePath, e);
                this.fileType = null;
            }
        }

        setId("FileEntry" + index);
        setProperty("Padding", Map.of("Full", 10));

        setStyle(Map.of(
            "Default", Map.of("Background", Color.of("#1a1a1a", 0.5f)),
            "Hovered", Map.of("Background", Color.of("#1a1a1a", 0.7f)),
            "Pressed", Map.of("Background", Color.of("#1a1a1a", 0.9f))
        ));

        initialize();
    }

    public FileBrowserFileEntry(Path filePath, int index, String fileName) {
        this(filePath, index);
        this.fileName = fileName;
    }

    /**
     * Initialize the file entry.
     */
    protected void initialize() {
        // Add the file icon to the line group
        fileIconSlot = createLineLabel(
            ComponentFactory.create(Image.class)
                .setSrc(FileBrowserIconRegistry.getFileIcon(fileType))
                .setSizes(24)
        );

        // Add the file name to the file group
        fileNameSlot = createLineLabel(getFileName());

        // Add the file size to the file group
        fileSizeSlot = createLineLabel(getFileSize());

        // Add the file last modified date to the file group
        fileLastModifiedDateSlot = createLineLabel(getFileLastModifiedDate());

        // If is a directory
        if (file.isDirectory()) {
            // Add the browse button to the file group
            setProperty("TooltipText", "Browse this directory");
        } else
        // If is editable
        if (isEditable()) {
            // Add the edit button to the file group
            fileActionSlot = createLineLabel(
                ComponentFactory.create(Button.class)
                    .appendChild(
                        ComponentFactory.create(Image.class)
                            .setSrc(FileBrowserIconRegistry.getIconPath("UI", "Edit.png"))
                            .setSizes(24)
                    )
                    .setId("EditButton")
                    .setProperty("TooltipText", "Edit this file")
            );
        } else {
            setProperty("TooltipText", "This file is not editable");
        }
    }

    /**
     * Get the index of the file entry.
     * @return The index of the file entry.
     */
    public int getIndex() {
        return index;
    }

    /**
     * Get the file.
     * @return The file.
     */
    public File getFile() {
        return file;
    }

    /**
     * Get the file name.
     * @return The file name.
     */
    private String getFileName() {
        return fileName != null ? fileName : file.getName();
    }

    /**
     * Whether the file entry is editable.
     * @return Whether the file entry is editable.
     */
    public boolean isEditable() {
        // Can't edit if it's not a file or is a directory
        if (!file.isFile() || file.isDirectory()) {
            return false;
        }

        // Can't edit if the file is not writable
        if (!file.canWrite()) {
            return false;
        }

        // If file type is not known, can't edit
        if (fileType == null) {
            return false;
        }

        // Can't edit audio, images and videos
        if (fileType.startsWith("audio/") || fileType.startsWith("image/") || fileType.startsWith("video/")) {
            return false;
        }

        // Can't edit if the file is too large
        if (file.length() > 1024 * 1024 * 10) {
            return false;
        }

        return true;
    }

    /**
     * Get the file size.
     * @return The file size.
     */
    private String getFileSize() {
        // Convert file size to appropriate units (bytes, KB, MB, GB, TB)
        long bytes = file.length();
        String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        double fileSize = (double) bytes;
        int unitIndex = 0;

        while (fileSize >= 1024 && unitIndex < units.length - 1) {
            fileSize /= 1024;
            unitIndex++;
        }

        String fileSizeUnit = units[unitIndex];
        fileSize = unitIndex == 0 ? (long) fileSize : fileSize;

        return String.format(unitIndex == 0 ? "%s %s" : "%.2f %s", fileSize, fileSizeUnit);
    }

    /**
     * Get the file last modified date.
     * @return The file last modified date.
     */
    private String getFileLastModifiedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(file.lastModified());
    }

    /**
     * Set the index of the file entry.
     * @param index The index of the file entry.
     * @return The file entry.
     */
    public FileBrowserFileEntry setIndex(int index) {
        this.index = index;
        return this;
    }
}

class FileBrowserLineLabel extends FileBrowserLine {
    public FileBrowserLineLabel() {
        super();

        // Create the file name slot
        fileNameSlot = createLineLabel("Name");

        // Create the file size slot
        fileSizeSlot = createLineLabel("Size");

        // Create the file last modified date slot
        fileLastModifiedDateSlot = createLineLabel("Last modified date");
    }
}

class FileBrowserLine extends ComponentBuilder {
    /**
     * The icon size.
     */
    public static final int ICON_SIZE = 24;

    /**
     * The file name size.
     */
    public static final int FILE_NAME_SIZE = 400;

    /**
     * The file size size.
     */
    public static final int FILE_SIZE_SIZE = 100;

    /**
     * The file last modified date size.
     */
    public static final int FILE_LAST_MODIFIED_DATE_SIZE = 250;

    /**
     * The file action size.
     */
    public static final int FILE_ACTION_SIZE = 24;

    /**
     * Get the total width of the line.
     * @return The total width of the line.
     */
    public static int getTotalWidth() {
        return ICON_SIZE + FILE_NAME_SIZE + FILE_SIZE_SIZE + FILE_LAST_MODIFIED_DATE_SIZE + FILE_ACTION_SIZE;
    }

    /**
     * The line group.
     */
    protected Group lineGroup;

    /**
     * The icon path.
     */
    protected ComponentBuilder fileIconSlot;

    /**
     * The file name slot.
     */
    protected ComponentBuilder fileNameSlot;

    /**
     * The file size slot.
     */
    protected ComponentBuilder fileSizeSlot;

    /**
     * The file last modified date slot.
     */
    protected ComponentBuilder fileLastModifiedDateSlot;

    /**
     * The file action slot.
     */
    protected ComponentBuilder fileActionSlot;

    public FileBrowserLine() {
        super("Button");

        // Create the line group
        lineGroup = (Group) ComponentFactory.create(Group.class)
            .setProperty("LayoutMode", LayoutMode.CENTER)
            .setProperty("Anchor", Map.of("Width", getTotalWidth()));
    }

    public String build() {
        lineGroup.appendChild(
            ComponentFactory.create(Group.class)
                .setProperty("Anchor", Map.of("Width", ICON_SIZE, "Height", ICON_SIZE))
                .setProperty("Padding", Map.of("Right", ICON_SIZE / 2))
                .appendChild(fileIconSlot)
        );

        lineGroup.appendChild(
            ComponentFactory.create(Group.class)
                .setProperty("Anchor", Map.of("Width", FILE_NAME_SIZE))
                .appendChild(fileNameSlot)
        );

        lineGroup.appendChild(
            ComponentFactory.create(Group.class)
                .setProperty("Anchor", Map.of("Width", FILE_SIZE_SIZE))
                .appendChild(fileSizeSlot)
        );

        lineGroup.appendChild(
            ComponentFactory.create(Group.class)
                .setProperty("Anchor", Map.of("Width", FILE_LAST_MODIFIED_DATE_SIZE))
                .appendChild(fileLastModifiedDateSlot)
        );

        lineGroup.appendChild(
            ComponentFactory.create(Group.class)
                .setProperty("Anchor", Map.of("Width", FILE_ACTION_SIZE, "Height", FILE_ACTION_SIZE))
                .appendChild(fileActionSlot)
        );

        // Append the line group to the component
        appendChild(lineGroup);

        return super.build();
    }

    /**
     * Create a line label.
     * @param text The text.
     * @return The line label.
     */
    protected ComponentBuilder createLineLabel(String text) {
        return createLineLabel(ComponentFactory.create(Label.class).setText(text));
    }

    /**
     * Create a line label.
     * @param component The component.
     * @return The line label.
     */
    protected ComponentBuilder createLineLabel(ComponentBuilder component) {
        return ComponentFactory.create(Group.class)
            .appendChild(component);
    }
}