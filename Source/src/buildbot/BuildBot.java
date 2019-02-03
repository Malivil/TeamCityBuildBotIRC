package buildbot;

import it.sauronsoftware.feed4j.FeedIOException;
import it.sauronsoftware.feed4j.FeedParser;
import it.sauronsoftware.feed4j.FeedXMLParseException;
import it.sauronsoftware.feed4j.UnsupportedFeedException;
import it.sauronsoftware.feed4j.bean.Feed;
import it.sauronsoftware.feed4j.bean.FeedItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.xerces.impl.dv.util.Base64;
import org.jibble.pircbot.Colors;
import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.PircBot;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class BuildBot extends PircBot
{
    // <editor-fold defaultstate="collapsed" desc="Variables">
    private String feedUrl;
    private String restUrl;
    private String restUser;
    private String restPassword;
    private String channelName;
    private int pollTime;
    private Timer timer;
    private static Logger logger = LogManager.getLogger();
    // </editor-fold>

    public BuildBot(String hostName, String channelName, String channelPassword, String botName, String feedUrl, String restUrl, String restUser, String restPassword, int pollTime)
            throws IOException, IrcException, FeedIOException, FeedXMLParseException, UnsupportedFeedException {
        setAutoNickChange(true);
        setName(botName);
        setLogin(botName);

        this.channelName = channelName;
        this.feedUrl = feedUrl;
        this.restUrl = restUrl;
        this.restUser = restUser;
        this.restPassword = restPassword;
        this.pollTime = pollTime;

        connect(hostName);
        if (channelPassword == null)
            joinChannel(channelName);
        else
            joinChannel(channelName, channelPassword);

        logger.info(String.format("Connected to '%s' as '%s'", getServer(), getNick()));
    }

    // <editor-fold defaultstate="collapsed" desc="Commands">
    @Override
    protected void onMessage(String channel, String sender, String login, String hostname, String message) {
        super.onMessage(channel, sender, login, hostname, message);

        try {
            String prefix = getNick().toLowerCase();
            // Check for "BuildBot:" before "BuildBot"
            if (message.toLowerCase().startsWith(prefix + ":"))
                prefix += ":";

            if (message.toLowerCase().startsWith(prefix)) {
                String command = message.substring(prefix.length()).trim();
                String[] paramTokens = command.split(" ");
                String params = "";

                // If there are parameters, join them into a single value to be used later
                if (paramTokens.length > 0) {
                    command = paramTokens[0].trim();

                    StringBuilder paramBuilder = new StringBuilder();
                    for (int i = 1; i < paramTokens.length; i++) {
                        String token = paramTokens[i];
                        if (token.length() == 0)
                            continue;
                        paramBuilder.append(token);
                        paramBuilder.append(" ");
                    }
                    params = paramBuilder.toString().trim();
                }

                logger.info("%s sent command '%s' with params '%s'", sender, command, params);

                if (command.equalsIgnoreCase("status")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Reports the status of the last build named 'BuildName'");
                        sendMessage(channelName, "Usage: status BuildName");
                        return;
                    }
                    printBuildStatus(params);
                }
                else if (command.equalsIgnoreCase("last")) {
                    int count = 5;
                    if (params.length() > 0) {
                        try {
                            count = Integer.parseInt(params);
                        }
                        catch (Exception ex) {
                            // No number specified, just use the default
                        }
                    }
                    printLastBuilds(count);
                }
                else if (command.equalsIgnoreCase("building")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Reports whether a build is occurring for a build named 'BuildName'");
                        sendMessage(channelName, "Usage: building BuildName");
                        return;
                    }

                    String buildType = getBuildType(params);
                    if (buildType == null) {
                        sendMessage(channelName, "No build named '" + params + "' found ");
                        return;
                    }

                    printIsBuilding(params, buildType);
                }
                else if (command.equalsIgnoreCase("start") || command.equalsIgnoreCase("build")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Starts a build with the given name");
                        sendMessage(channelName, "Usage: start BuildName");
                        return;
                    }

                    String buildType = getBuildType(params);
                    if (buildType == null) {
                        sendMessage(channelName, "No build named '" + params + "' found ");
                        return;
                    }

                    startBuild(params, buildType);
                }
                else if (command.equalsIgnoreCase("stop") || command.equalsIgnoreCase("cancel")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Stops a build with the given name");
                        sendMessage(channelName, "Usage: stop BuildName");
                        return;
                    }

                    String buildType = getBuildType(params);
                    if (buildType == null) {
                        sendMessage(channelName, "No build named '" + params + "' found ");
                        return;
                    }

                    stopBuild(params, buildType);
                }
                else if (command.equalsIgnoreCase("pin") || command.equalsIgnoreCase("tag")) {
                    if (paramTokens.length <= 2) {
                        sendMessage(channelName, "Pins a build with the given 'BuildId' and adds the optionally specified tags");
                        sendMessage(channelName, "Usage: pin BuildId [Tag(s)]");
                        return;
                    }

                    pinBuild(paramTokens);
                }
                else if (command.equalsIgnoreCase("tags")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Reports the tags for the last build named 'BuildName' or the specific build by 'BuildId'");
                        sendMessage(channelName, "Usage: tags BuildId|BuildName");
                        return;
                    }

                    printTags(params);
                }
                else if (command.equalsIgnoreCase("tagged")) {
                    if (params.length() == 0 || params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Reports the latest builds with the given tag");
                        sendMessage(channelName, "Usage: tagged Tag");
                        return;
                    }

                    printTaggedBuilds(params);
                }
                else if (command.equalsIgnoreCase("istagged")) {
                    if (paramTokens.length <= 2) {
                        sendMessage(channelName, "Reports whether the latest build named 'BuildName' has the given tag");
                        sendMessage(channelName, "Usage: istagged BuildName Tag");
                        return;
                    }

                    printIsBuildTagged(paramTokens);
                }
                else if (command.equalsIgnoreCase("pinned")) {
                    if (params.equalsIgnoreCase("help")) {
                        sendMessage(channelName, "Reports the latest pinned builds, filtered optionally by BuildName");
                        sendMessage(channelName, "Usage: pinned [BuildName]");
                        return;
                    }

                    if (params.length() == 0)
                        printPinnedBuilds();
                    else {
                        String buildType = getBuildType(params);
                        if (buildType == null) {
                            sendMessage(channelName, "No build named '" + params + "' found ");
                            return;
                        }

                        printPinnedBuild(params, buildType);
                    }
                }
                else {
                    sendMessage(channelName, "Commands:");
                    sendMessage(channelName, "status BuildName | last [count]");
                    sendMessage(channelName, "building BuildName");
                    sendMessage(channelName, "start BuildName | stop BuildName");
                    sendMessage(channelName, "pin BuildId [Tag(s)] | pinned BuildName");
                    sendMessage(channelName, "tags BuildName|BuildId | tagged Tag | istagged BuildName Tag");
                }
            }
        }
        catch (Exception ex) {
            logger.error(String.format("Error occurred running command '%s'", message), ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error occurred running command, see logs.");
        }
    }

    private void startBuild(String buildName, String buildType) throws IOException {
        sendPostRequest(String.format("/buildQueue", buildType), "POST", String.format("<build><buildType id='%s'/></build>", buildType));
        sendMessage(channelName, "Build for '" + buildName + "' started");
    }

    private void stopBuild(String buildName, String buildType) throws IOException {
        sendPostRequest(String.format("/builds/running:true,buildType:%s", buildType), "POST", "<buildCancelRequest readdIntoQueue='false' />");
        sendMessage(channelName, "Build for '" + buildName + "' stopped");
    }

    private void pinBuild(String[] params) throws IOException, ParserConfigurationException, TransformerException {
        String buildId = params[1].trim();
        String message = "Build %s pinned";

        // Only tag the build if we actually have tags
        if (params.length > 2) {
            // Create the XML Document
            DocumentBuilderFactory icFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder icBuilder = icFactory.newDocumentBuilder();
            Document doc = icBuilder.newDocument();
            // Add the "tags" root node
            Element rootEl = doc.createElement("tags");
            doc.appendChild(rootEl);

            // Add all of the "Tag" elements
            for (int i = 2; i < params.length; i++) {
                String tag = params[i].trim();
                Element tagEl = doc.createElement("tag");
                tagEl.appendChild(doc.createTextNode(tag));
                rootEl.appendChild(tagEl);
            }

            // Convert the XML Document to a string
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            String output = writer.getBuffer().toString().replaceAll("\n|\r", "");

            // Tag the build
            sendPostRequest(String.format("/builds/id:%s/tags/", buildId), "POST", output);
            message += " and tagged";
        }

        // Pin the build
        sendPostRequest(String.format("/builds/id:%s/pin/", buildId), "PUT", null);

        sendMessage(channelName, String.format(message, buildId));
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="HTTP Request Methods">
    private void sendPostRequest(String restPath, String method, String data) throws IOException {
        HttpURLConnection httpCon = getRestConnection(restPath, method);
        if (data != null) {
            httpCon.setRequestProperty("Content-Type", "application/xml");
            OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());
            out.write(data);
            out.close();
        }

        int response = httpCon.getResponseCode();
        if (response >= 400)
            throw new IOException(response + ": " + httpCon.getResponseMessage());
    }

    private Document sendGetRequest(String restPath) throws IOException, SAXException, ParserConfigurationException {
        HttpURLConnection httpCon = getRestConnection(restPath, "GET");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(httpCon.getInputStream());
    }

    private HttpURLConnection getRestConnection(String restPath, String method) throws IOException {
        HttpURLConnection httpCon = (HttpURLConnection)new URL(String.format("%s%s", restUrl, restPath)).openConnection();
        httpCon.setDoOutput(true);
        httpCon.setRequestMethod(method);
        httpCon.setRequestProperty("Authorization", "Basic " + Base64.encode((restUser + ":" + restPassword).getBytes()));
        return httpCon;
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Reporting Operations">
    @Override
    protected void onJoin(String channel, String sender, String login, String hostname) {
        super.onJoin(channel, sender, login, hostname);

        // Get the list of builds and print the most recent unknown one
        if (sender.equals(getNick())) {
            getFeed(1);
            logger.info(String.format("Joined '%s'", channelName));
        }
    }

    private void getFeed() {
        getFeed(-1);
    }

    private void getFeed(int count) {
        if (timer != null)
            timer.cancel();

        try {
            Feed feed = FeedParser.parse(new URL(feedUrl));
            // Loop backwards so we see the oldest ones first
            for (int i = feed.getItemCount() - 1; i >= 0; i--) {
                FeedItem item = feed.getItem(i);
                BuildItem build = new BuildItem(item.getTitle(), item.getGUID(), item.getLink());

                // Save the build if it's new
                if (isNewBuild(build.getGUID())) {
                    addBuild(build);

                    // Only print the last "count" number of builds, if "count" is specified
                    if (count < 0 || i < count)
                        printBuild(build);
                }
            }
        }
        catch (Exception ex) {
            logger.error("Error parsing the build feed", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error parsing the build feed, see logs.");
        }

        // Set timer to get the next feed
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                getFeed();
            }
        }, pollTime * 1000);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Print Methods">
    private void printBuild(BuildItem build) {
        sendMessage(channelName, build.toString());
        sendMessage(channelName, build.getLink().toString());
    }

    private void printBuildStatus(String buildName) {
        BuildItem build = getBuildInfo(buildName);
        if (build != null)
            sendMessage(channelName, "Last Build: " + build.toString());
        else
            sendMessage(channelName, "No build named '" + buildName + "' found");
    }

    private void printLastBuilds(int count) {
        ArrayList<BuildItem> builds = getLastBuilds(count);
        if (builds != null) {
            for (int i = 0; i < builds.size(); i++)
                sendMessage(channelName, "Build #" + (i + 1) + ": " + builds.get(i).toString());
        }
        else
            sendMessage(channelName, "No builds found");
    }

    private void printTags(String build) {
        String tags = getTags(build, true);
        if (tags != null)
            sendMessage(channelName, tags);
    }

    private String getTags(String build, boolean giveEmptyMessage) {
        try {
            boolean isBuildId = false;
            try {
                Integer.parseInt(build);
                isBuildId = true;
            }
            catch (Exception ex) {
                // It's not a number
            }
            Document tagsDoc = sendGetRequest(String.format("/builds/%s:%s/tags", (isBuildId ? "id" : "buildType"), build));
            Element rootEl = tagsDoc.getDocumentElement();
            NodeList tagEls = rootEl.getElementsByTagName("tag");
            StringBuilder tags = new StringBuilder();

            if (tagEls.getLength() == 0) {
                if (giveEmptyMessage) {
                    if (isBuildId)
                        return "No tags found for build " + build;
                    else
                        return String.format("Latest build of %s doesn't have any tags", build);
                }
                else
                    return null;
            }
            else {
                for (int i = 0, length = tagEls.getLength(); i < length; i++) {
                    Node tagEl = tagEls.item(i).getFirstChild();
                    if (tagEl == null)
                        continue;
                    if (i > 0)
                        tags.append(", ");
                    tags.append(tagEl.getNodeValue());
                }

                return "Tags: " + tags.toString();
            }
        }
        catch (Exception ex) {
            logger.error("Error getting tags for build " + build, ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting tags for build " + build + ", see logs.");
        }

        return null;
    }

    private void printTaggedBuilds(String tag) {
        try {
            Document tagsDoc = sendGetRequest(String.format("/builds?locator=running:false,status:SUCCESS,tags:%s", tag));
            Element rootEl = tagsDoc.getDocumentElement();
            NodeList buildEls = rootEl.getChildNodes();

            if (buildEls.getLength() == 0)
                sendMessage(channelName, String.format("No builds were found with the tag '%s'", tag));
            else {
                HashMap<String, BuildItem> builds = new HashMap<String, BuildItem>();

                for (int i = 0, length = buildEls.getLength(); i < length; i++) {
                    Node buildEl = buildEls.item(i);
                    NamedNodeMap attributes = buildEl.getAttributes();
                    Node attributeEl = attributes.getNamedItem("buildTypeId");
                    if (attributeEl == null)
                        continue;

                    String buildType = attributeEl.getNodeValue();
                    if (builds.containsKey(buildType))
                        continue;

                    int buildId = -1;
                    try {
                        attributeEl = attributes.getNamedItem("id");
                        if (attributeEl != null)
                            buildId = Integer.parseInt(attributeEl.getNodeValue());
                    }
                    catch (Exception ex) {
                        // No build ID for whatever reason
                    }

                    String buildNumber = "Unknown";
                    try {
                        attributeEl = attributes.getNamedItem("number");
                        if (attributeEl != null)
                            buildNumber = attributeEl.getNodeValue();
                    }
                    catch (Exception ex) {
                        // No build number for whatever reason
                    }

                    BuildItem build = getBuildInfo(buildType, true);
                    if (build == null)
                        build = new BuildItem("Unknown Build", buildNumber, true, null, null, buildType);
                    else
                        build.setBuildNumber(buildNumber);
                    build.setBuildId(buildId);

                    builds.put(buildType, build);
                }

                String[] buildTypes = builds.keySet().toArray(new String[] {});
                if (buildTypes.length == 0)
                    sendMessage(channelName, "No builds were found with the tag '" + tag + "'");
                else {
                    for (int i = 0, length = buildTypes.length; i < length; i++) {
                        BuildItem build = builds.get(buildTypes[i]);
                        String buildInfo = String.format("%s #%s with Build ID %d", build.getName(), build.getBuildNumber(), build.getBuildId());
                        sendMessage(channelName, buildInfo);
                    }
                }
            }
        }
        catch (Exception ex) {
            logger.error("Error getting tagged builds", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting tagged builds, see logs.");
        }
    }

    private void printIsBuildTagged(String[] params) {
        String build = params[1].trim();
        String tag = params[2].trim();

        try {
            Document tagsDoc = sendGetRequest(String.format("/builds/buildType:%s", build));
            Element rootEl = tagsDoc.getDocumentElement();
            NodeList tagEls = rootEl.getElementsByTagName("tag");

            for (int i = 0, length = tagEls.getLength(); i < length; i++) {
                Node tagEl = tagEls.item(i).getFirstChild();
                if (tagEl == null)
                    continue;
                String buildTag = tagEl.getNodeValue();
                if (tag.equalsIgnoreCase(buildTag)) {
                    sendMessage(channelName, "Yes, the latest build of " + build + " is tagged '" + tag + "'");
                    return;
                }
            }

            sendMessage(channelName, "No, the latest build of " + build + " is not tagged '" + tag + "'");
        }
        catch (Exception ex) {
            logger.error("Error checking if latest build of " + build + " is tagged '" + tag + "'", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error checking if latest build of " + build + " is tagged '" + tag + "', see logs.");
        }
    }

    private void printPinnedBuilds() {
        try {
            Document tagsDoc = sendGetRequest("/builds?locator=pinned:true");
            Element rootEl = tagsDoc.getDocumentElement();
            NodeList buildEls = rootEl.getChildNodes();

            if (buildEls.getLength() == 0)
                sendMessage(channelName, String.format("No pinned builds were found"));
            else {
                HashMap<String, BuildItem> builds = new HashMap<String, BuildItem>();

                for (int i = 0, length = buildEls.getLength(); i < length; i++) {
                    Node buildEl = buildEls.item(i);
                    NamedNodeMap attributes = buildEl.getAttributes();
                    Node attributeEl = attributes.getNamedItem("buildTypeId");
                    if (attributeEl == null)
                        continue;

                    String buildType = attributeEl.getNodeValue();
                    if (builds.containsKey(buildType))
                        continue;

                    int buildId = -1;
                    try {
                        attributeEl = attributes.getNamedItem("id");
                        if (attributeEl != null)
                            buildId = Integer.parseInt(attributeEl.getNodeValue());
                    }
                    catch (Exception ex) {
                        // No build ID for whatever reason
                    }

                    String buildNumber = "Unknown";
                    try {
                        attributeEl = attributes.getNamedItem("number");
                        if (attributeEl != null)
                            buildNumber = attributeEl.getNodeValue();
                    }
                    catch (Exception ex) {
                        // No build number for whatever reason
                    }

                    BuildItem build = getBuildInfo(buildType, true);
                    if (build == null)
                        build = new BuildItem("Unknown Build", buildNumber, true, null, null, buildType);
                    else
                        build.setBuildNumber(buildNumber);
                    build.setBuildId(buildId);

                    builds.put(buildType, build);
                }

                String[] buildTypes = builds.keySet().toArray(new String[] {});
                if (buildTypes.length == 0)
                    sendMessage(channelName, "No pinned builds were found");
                else {
                    for (int i = 0, length = buildTypes.length; i < length; i++) {
                        BuildItem build = builds.get(buildTypes[i]);
                        String tags = getTags(build.getBuildId() + "", false);
                        if (tags == null)
                            tags = "no tags";
                        String buildInfo = String.format("%s #%s with Build ID %d and %s", build.getName(), build.getBuildNumber(), build.getBuildId(), tags);
                        sendMessage(channelName, buildInfo);
                    }
                }
            }
        }
        catch (Exception ex) {
            logger.error("Error getting pinned builds", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting pinned builds, see logs.");
        }
    }

    private void printPinnedBuild(String buildName, String buildType) {
        try {
            Document tagsDoc = sendGetRequest(String.format("/builds/?locator=pinned:true,count:1,buildType:%s", buildType));
            Element rootEl = tagsDoc.getDocumentElement();
            Node buildEl = rootEl.getFirstChild();

            if (buildEl == null)
                sendMessage(channelName, String.format("No pinned builds were found for '%s'", buildName));
            else {
                NamedNodeMap attributes = buildEl.getAttributes();
                Node attributeEl = attributes.getNamedItem("buildTypeId");
                if (attributeEl == null)
                    throw new Exception("No 'buildTypeId' attribute found in resulting build XML");

                int buildId = -1, buildNumber = -1;
                try {
                    attributeEl = attributes.getNamedItem("id");
                    if (attributeEl != null)
                        buildId = Integer.parseInt(attributeEl.getNodeValue());
                }
                catch (Exception ex) {
                    // No build ID for whatever reason
                }

                try {
                    attributeEl = attributes.getNamedItem("number");
                    if (attributeEl != null)
                        buildNumber = Integer.parseInt(attributeEl.getNodeValue());
                }
                catch (Exception ex) {
                    // No build number for whatever reason
                }

                BuildItem build = getBuildInfo(buildType, true);
                String name = "Unknown Build";
                if (build != null)
                    name = build.getName();

                String tags = getTags(buildId + "", false);
                if (tags == null)
                    tags = "no tags";
                String buildInfo = String.format("%s #%s with Build ID %d and %s", name, buildNumber, buildId, tags);
                sendMessage(channelName, buildInfo);
            }
        }
        catch (Exception ex) {
            logger.error("Error getting pinned status", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting pinned status, see logs.");
        }
    }

    private void printIsBuilding(String buildName, String buildType) {
        try {
            Document tagsDoc = sendGetRequest(String.format("/builds?locator=running:true,buildType:%s", buildType));
            Element rootEl = tagsDoc.getDocumentElement();
            String countStr = rootEl.getAttribute("count");

            // If "count" attribute > 0, it's building
            int count = Integer.parseInt(countStr);
            sendMessage(channelName, "An instance of '" + buildName + "' is " + ((count > 0) ? "" : "NOT ") + "building");
        }
        catch (Exception ex) {
            logger.error("Error getting isBuilding status", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting isBuilding status, see logs.");
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Database Operations">
    // http://www.tutorialspoint.com/sqlite/sqlite_java.htm
    private Connection openDatabase(String filePath) {
        try {
            Class.forName("org.sqlite.JDBC");
            return DriverManager.getConnection("jdbc:sqlite:" + filePath);
        }
        catch (Exception ex) {
            logger.error("Error connecting to database", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error connecting to database, see logs.");
        }
        return null;
    }

    private boolean isNewBuild(String guid) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        boolean isNew = false;
        try {
            conn = openDatabase("BuildBot.s3db");
            if (conn == null)
                return false;
            conn.setAutoCommit(false);

            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT GUID FROM Builds WHERE GUID = '" + guid + "'");
            if (!rs.next())
                isNew = true;

            conn.commit();
        }
        catch (Exception ex) {
            logger.error("Error checking if Build with GUID '" + guid + "' exists", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error checking if Build with GUID '" + guid + "' exists, see logs.");
        }
        finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            }
            catch (Exception ex) {
                logger.error("Error closing connection to database", ex);
                sendMessage(channelName, Colors.RED + "ERROR: Error closing connection to database, see logs.");
            }
        }

        return isNew;
    }

    private String getBuildType(String buildName) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        String buildType = null;
        try {
            conn = openDatabase("BuildBot.s3db");
            if (conn == null)
                return null;
            conn.setAutoCommit(false);

            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT BuildType FROM Builds WHERE Name LIKE '" + buildName + "%' ORDER BY BuildID DESC LIMIT 1");
            if (rs.next())
                buildType = rs.getString("BuildType");

            conn.commit();
        }
        catch (Exception ex) {
            logger.error("Error getting the build type for build named '" + buildName + "'", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting the build type for build named '" + buildName + "', see logs.");
        }
        finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            }
            catch (Exception ex) {
                logger.error("Error closing connection to database", ex);
                sendMessage(channelName, Colors.RED + "ERROR: Error closing connection to database, see logs.");
            }
        }

        return buildType;
    }

    private BuildItem getBuildInfo(String buildName) {
        return getBuildInfo(buildName, false);
    }

    private BuildItem getBuildInfo(String build, boolean byType) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        BuildItem item = null;
        try {
            conn = openDatabase("BuildBot.s3db");
            if (conn == null)
                return null;
            conn.setAutoCommit(false);

            stmt = conn.createStatement();
            String column = byType ? "BuildType" : "Name";
            rs = stmt.executeQuery(String.format("SELECT BuildID, Name, Link, GUID, BuildNumber, WasSuccessful, BuildType FROM Builds WHERE %s LIKE '%s' ORDER BY BuildID DESC LIMIT 1", column, build + (byType ? "" : "%")));
            if (rs.next())
                item = readBuildInfo(rs);

            conn.commit();
        }
        catch (Exception ex) {
            logger.error("Error getting build information for build '" + build + "'", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting build information for build '" + build + "', see logs.");
        }
        finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            }
            catch (Exception ex) {
                logger.error("Error closing connection to database", ex);
                sendMessage(channelName, Colors.RED + "ERROR: Error closing connection to database, see logs.");
            }
        }

        return item;
    }

    private ArrayList<BuildItem> getLastBuilds(int count) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        ArrayList<BuildItem> builds = null;
        try {
            conn = openDatabase("BuildBot.s3db");
            if (conn == null)
                return null;
            conn.setAutoCommit(false);

            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT BuildID, Name, Link, GUID, BuildNumber, WasSuccessful, BuildType FROM Builds ORDER BY BuildID DESC LIMIT " + count);
            while (rs.next()) {
                if (builds == null)
                    builds = new ArrayList<BuildItem>();
                builds.add(readBuildInfo(rs));
            }

            conn.commit();
        }
        catch (Exception ex) {
            logger.error("Error getting build last " + count + " builds", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error getting build last " + count + " builds, see logs.");
        }
        finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            }
            catch (Exception ex) {
                logger.error("Error closing connection to database", ex);
                sendMessage(channelName, Colors.RED + "ERROR: Error closing connection to database, see logs.");
            }
        }

        return builds;
    }

    private BuildItem readBuildInfo(ResultSet rs)
            throws IOException, SQLException {
        return new BuildItem(rs.getString("Name"), rs.getString("BuildNumber"), rs.getInt("WasSuccessful") == 1, rs.getString("GUID"), new URL(rs.getString("Link")), rs.getString("BuildType"));
    }

    private void addBuild(BuildItem build) {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = openDatabase("BuildBot.s3db");
            if (conn == null)
                return;
            conn.setAutoCommit(false);

            stmt = conn.createStatement();
            stmt.executeUpdate("INSERT INTO Builds (Name, Link, GUID, BuildNumber, WasSuccessful, BuildType) VALUES ('" + build.getName() + "', '" + build.getLink() + "', '" + build.getGUID() + "', '" + build.getBuildNumber() + "', " + (build.wasBuildSuccessful() ? 1 : 0) + ", '" + build.getBuildType() + "')");

            conn.commit();
        }
        catch (Exception ex) {
            logger.error("Error saving Build to database", ex);
            sendMessage(channelName, Colors.RED + "ERROR: Error saving Build to database, see logs.");
        }
        finally {
            try {
                if (stmt != null)
                    stmt.close();
                if (conn != null)
                    conn.close();
            }
            catch (Exception ex) {
                logger.error("Error closing connection to database", ex);
                sendMessage(channelName, Colors.RED + "ERROR: Error closing connection to database, see logs.");
            }
        }
    }
    // </editor-fold>
}
