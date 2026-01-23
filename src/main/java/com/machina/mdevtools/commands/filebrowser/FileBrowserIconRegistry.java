package com.machina.mdevtools.commands.filebrowser;

import java.util.HashMap;
import java.util.Map;

public class FileBrowserIconRegistry {
    /**
     * The path to the icons.
     */
    public static final String ICON_PATH = "Common/MDevTools/Icons/";

    /**
     * The icons.
     */
    private static final Map<String, String> ICONS = new HashMap<>();

    static {
        ICONS.put("directory", getIconPath("Files", "Directory.png"));

        ICONS.put("image/*", getIconPath("Files", "Image.png"));
        ICONS.put("text/*", getIconPath("Files", "Text.png"));
        ICONS.put("video/*", getIconPath("Files", "Video.png"));
        ICONS.put("audio/*", getIconPath("Files", "Audio.png"));

        ICONS.put("*javascript", getIconPath("Files", "JavaScript.png"));
        ICONS.put("*python", getIconPath("Files", "Python.png"));
        ICONS.put("*java", getIconPath("Files", "Java.png"));


        ICONS.put("application/zip", getIconPath("Files", "Zip.png"));
        ICONS.put("application/json", getIconPath("Files", "JSON.png"));
        ICONS.put("application/xml", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-yaml", "Common/MDevTools/Icons/Code.png");
        ICONS.put("application/x-kotlin", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-csharp", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-php", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-ruby", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-perl", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-lua", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-shellscript", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-sh", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-bash", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-powershell", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-batchfile", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-perl", getIconPath("Files", "Code.png"));
        ICONS.put("application/x-lua", getIconPath("Files", "Code.png"));
    }

    /**
     * Get the path to the icons.
     * @return The path to the icons.
     */
    public static String getIconPath(String... path) {
        return ICON_PATH + String.join("/", path).replace("\\", "/").replace("^/", "");
    }

    /**
     * Get the icon for a given file type.
     * @param fileType The file type.
     * @return The icon.
     */
    public static String getFileIcon(String fileType) {
        // If the file type is null, return the default file icon
        if (fileType == null) {
            return getIconPath("Files", "DefaultFile.png");
        }

        // Iterate through the icons and return the first one that matches the file type
        for (var entry : ICONS.entrySet()) {
            if (fileType.matches("^" + entry.getKey().replace("*", ".*") + "$")) {
                return entry.getValue();
            }
        }

        // If no icon is found, return the default file icon
        return getIconPath("Files", "DefaultFile.png");
    }
}
