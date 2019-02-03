package buildbot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.naming.ConfigurationException;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class BuildBotMain
{
    private static Logger logger = LogManager.getLogger();

    public static void main(String[] args) {
        try {
            Map<String, String> config = loadConfig();
            Set<String> keys = config.keySet();

            if (!keys.contains("hostName"))
                throw new ConfigurationException("No hostName parameter specified in the config file");
            if (!keys.contains("channelName"))
                throw new ConfigurationException("No channelName parameter specified in the config file");
            if (!keys.contains("botName"))
                throw new ConfigurationException("No botName parameter specified in the config file");
            if (!keys.contains("feedUrl"))
                throw new ConfigurationException("No feedUrl parameter specified in the config file");
            if (!keys.contains("pollTime"))
                throw new ConfigurationException("No pollTime parameter specified in the config file");
            if (!keys.contains("restUrl"))
                throw new ConfigurationException("No restUrl parameter specified in the config file");
            if (!keys.contains("restUser"))
                throw new ConfigurationException("No restUser parameter specified in the config file");
            if (!keys.contains("restPassword"))
                throw new ConfigurationException("No restPassword parameter specified in the config file");

            String hostName = config.get("hostName");
            String channelName = config.get("channelName");
            String channelPassword = config.get("channelPassword");
            String botName = config.get("botName");
            String feedUrl = config.get("feedUrl");
            String restUrl = config.get("restUrl");
            String restUser = config.get("restUser");
            String restPassword = config.get("restPassword");
            int pollTime = Integer.parseInt(config.get("pollTime"));

            new BuildBot(hostName, channelName, channelPassword, botName, feedUrl, restUrl, restUser, restPassword, pollTime);
        }
        catch (Exception ex) {
            logger.error("Exception occurred during Configuration parsing", ex);
        }
    }

    private static Map<String, String> loadConfig() {
        Map<String, String> config = new HashMap<String, String>();
        try {
            File configFile = new File("config.txt");
            if (!configFile.exists())
                throw new FileNotFoundException("Configuration file 'config.txt' not found in execution path");

            Scanner sc = new Scanner(configFile);
            while (sc.hasNext())
                config.put(sc.next(), sc.next());
        }
        catch (FileNotFoundException ex) {
            logger.error("Exception occurred during Configuration parsing", ex);
        }
        return config;
    }
}
