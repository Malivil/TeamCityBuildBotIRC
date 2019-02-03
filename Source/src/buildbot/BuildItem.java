package buildbot;

import org.jibble.pircbot.Colors;

import java.net.URL;

public class BuildItem
{
    private String guid;
    private URL link;
    private String name;
    private String buildType;
    private String buildNumber;
    private int buildId;
    private boolean wasSuccessful;

    public BuildItem(String title, String guid, URL link) {
        this.guid = guid;
        this.link = link;

        // Strip out the project name prefix prefix
        title = title.substring(title.indexOf("::") + 2);

        int buildLoc = title.indexOf("#");
        name = title.substring(0, buildLoc).trim();
        buildNumber = title.substring(buildLoc + 1, title.indexOf(" ", buildLoc));
        wasSuccessful = title.substring(title.lastIndexOf(" ")).trim().equals("successful");

        this.buildType = parseBuildType();
    }

    public BuildItem(String name, String buildNumber, boolean wasSuccessful, String guid, URL link, String buildType) {
        this.name = name;
        this.buildNumber = buildNumber;
        this.wasSuccessful = wasSuccessful;
        this.guid = guid;
        this.link = link;
        this.buildType = buildType;
    }

    private String parseBuildType() {
        String url = link.toString();
        String buildType = "&buildTypeId=";
        int btLoc = url.indexOf(buildType);
        if (btLoc < 0)
            return null;

        int nextParamLoc = url.indexOf("&", btLoc + 1);
        if (nextParamLoc > 0)
            return url.substring(btLoc + buildType.length(), nextParamLoc);
        return url.substring(btLoc + buildType.length());
    }

    public String getName() {
        return name;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    public void setBuildNumber(String buildNumber) {
        this.buildNumber = buildNumber;
    }

    public int getBuildId() {
        return buildId;
    }

    public void setBuildId(int buildId) {
        this.buildId = buildId;
    }

    public boolean wasBuildSuccessful() {
        return wasSuccessful;
    }

    public String getGUID() {
        return guid;
    }

    public URL getLink() {
        return link;
    }

    public String getBuildType() {
        return buildType;
    }

    @Override
    public String toString() {
        return String.format("%s #%s %s%s", getName(), getBuildNumber(), wasBuildSuccessful() ? Colors.GREEN : Colors.RED, wasBuildSuccessful() ? "succeeded" : "failed");
    }
}
