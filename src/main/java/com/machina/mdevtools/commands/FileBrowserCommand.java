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
import com.machina.minterfacebuilder.util.customui.components.HButton;
import com.machina.minterfacebuilder.util.customui.components.HTitle;
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
            .setProperty("LayoutMode", LayoutMode.TOP);

        // Create the file entries
        listCurrentPathFiles();

        getDialog().setTitle(
            ComponentFactory.create(HTitle.class).setText("File Browser")
        );

        // Add the file list group to the page
        updateListUI();
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

        // List the files in the current path
        for (FileBrowserFileEntry fileEntry : getFileEntries()) {
            // Set the index
            fileEntry.setIndex(index++);

            // Add the file entry to the file list group
            fileListGroup.appendChild(fileEntry);

            // If it's a directory
            if (fileEntry.getFile().isDirectory()) {
                // Add an event listener for the directory buttons
                addEventListener(EventType.CLICK, "#Browse-" + fileEntry.getIndex(), (event) -> {
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
                addEventListener(EventType.CLICK, "#Edit-" + fileEntry.getIndex(), (event) -> {
                    // Set the current file
                    setCurrentFile(fileEntry);
                });
            }
        }

        // Add the file list group to the dialog
        getDialog().setContent(fileListGroup);
    }
}

class FileBrowserFileEntry extends ComponentBuilder {
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
        super("Group");

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
    }

    public FileBrowserFileEntry(Path filePath, int index, String fileName) {
        this(filePath, index);
        this.fileName = fileName;
    }

    @Override
    public String build() {
        // Create the file group (the base container)
        var fileGroup = ComponentFactory.create(Group.class)
            .setProperty("Padding", Map.of("Full", 10))
            .setProperty("Background", Color.of("#1a1a1a", 0.5f));

        // Add the file icon to the file group
        fileGroup.appendChild(createLineLabel(
            ComponentFactory.create(Image.class)
                .setSrc(FileBrowserIconRegistry.getFileIcon(fileType))
                .setSizes(24)
        ));

        // Add the file name to the file group
        fileGroup.appendChild(createLineLabel(getFileName()));

        // Add the file size to the file group
        fileGroup.appendChild(createLineLabel(getFileSize()));

        // Add the file last modified date to the file group
        fileGroup.appendChild(createLineLabel(getFileLastModifiedDate()));

        // If is a directory
        if (file.isDirectory()) {
            // Add the browse button to the file group
            fileGroup.setId("Browse-" + index);
        } else
        // If is editable
        if (isEditable()) {
            // Add the edit button to the file group
            fileGroup.appendChild(
                ComponentFactory.create(HButton.class)
                    .setIcon(FileBrowserIconRegistry.getIconPath("UI", "Edit.png"))
                        .setIconHeight(24)
                        .setIconWidth(24)
                        .setId("Edit-" + index)
                        .setProperty("FlexWeight", 1)
            );
        }

        appendChild(fileGroup);

        return super.build();
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
     * Create a line label.
     * @param text The text.
     * @return The line label.
     */
    private ComponentBuilder createLineLabel(String text) {
        return createLineLabel(ComponentFactory.create(Label.class).setText(text));
    }

    /**
     * Create a line label.
     * @param component The component.
     * @return The line label.
     */
    private ComponentBuilder createLineLabel(ComponentBuilder component) {
        return ComponentFactory.create(Group.class)
            .setProperty("FlexWeight", 1)
            .appendChild(component);
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