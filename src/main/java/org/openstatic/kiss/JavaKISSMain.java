package org.openstatic.kiss;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.cli.*;
import org.json.JSONObject;

public class JavaKISSMain implements AX25PacketListener, Runnable
{
    private KISSClient kClient;
    private Thread mainThread;
    private SimpleDateFormat simpleDateFormat;;

    public static File logsFolder;
    public static JSONObject settings;
    public static File settingsFile;
    public static APIWebServer apiWebServer;

    public JavaKISSMain(KISSClient client)
    {
        this.kClient = client;
        String pattern = "HH:mm:ss yyyy-MM-dd";
        this.simpleDateFormat = new SimpleDateFormat(pattern);
        client.addAX25PacketListener(this);
        this.mainThread = new Thread(this);
        if (JavaKISSMain.settings.optBoolean("txTest", false))
            this.mainThread.start();
        if (JavaKISSMain.settings.has("apiPort"))
        {
            JavaKISSMain.apiWebServer = new APIWebServer(client);
            JavaKISSMain.apiWebServer.setState(true);
        }
    }

    public void run()
    {
        int seq = 1;
        while(true)
        {
            try
            {
                Thread.sleep(JavaKISSMain.settings.optLong("txTestInterval", 10000));
                String tsString = String.valueOf(System.currentTimeMillis());
                String seqString = String.valueOf(seq);
                String payload = "JAXT Test Transmission @" + tsString + " #" + seqString;
                if (settings.has("payload"))
                {
                    payload = settings.optString("payload").replaceAll(Pattern.quote("{{ts}}"), tsString).replaceAll(Pattern.quote("{{seq}}"), tsString);
                }
                AX25Packet packet = new AX25Packet(settings.optString("source", "NOCALL1"), settings.optString("destination", "NOCALL2"), payload);
                kClient.send(packet);

                jsonLogAppend("tx.json", packet.toJSONObject());

                if (JavaKISSMain.settings.optBoolean("verbose", false))
                {
                    System.err.println("[" + this.simpleDateFormat.format(packet.getTimestamp()) + "] (Tx) " + packet.toLogString());
                }
                seq++;
            } catch (Exception e) {
                log(e);
            }
        }
    }

    public static void main(String[] args)
    {
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        CommandLine cmd = null;
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        Option testOption = new Option("t", "test", true, "Send test packets (optional parameter interval in seconds, default is 10 seconds)");
        testOption.setOptionalArg(true);
        options.addOption(testOption);
        options.addOption(new Option("h", "host", true, "Specify TNC host (Default: 127.0.0.1)"));
        options.addOption(new Option("p", "port", true, "KISS Port (Default: 8100)"));
        Option apiOption = new Option("a", "api", true, "Enable API Web Server, and specify port (Default: 8101)");
        apiOption.setOptionalArg(true);
        options.addOption(apiOption);
        options.addOption(new Option("f", "config-file", true, "Specify config file (.json)"));
        Option loggingOption = new Option("l", "logs", true, "Enable Logging, and optionally specify a directory");
        loggingOption.setOptionalArg(true);
        options.addOption(loggingOption);
        options.addOption(new Option("s", "source", true, "Source callsign (test payload)"));
        options.addOption(new Option("d", "destination", true, "Destination callsign (test payload)"));
        options.addOption(new Option("m", "payload", true, "Payload string to send on test interval. {{ts}} for timestamp, {{seq}} for sequence."));
        options.addOption(new Option("v", "verbose", false, "Shows Packets"));
        options.addOption(new Option("x", "post", true, "HTTP POST packets received as JSON to url"));
        options.addOption(new Option("?", "help", false, "Shows help"));

        JavaKISSMain.settings = new JSONObject();
        JavaKISSMain.settingsFile = null;
        File homeSettings = new File(System.getProperty("user.home"),".jaxt.json");
        if (homeSettings.exists())
        {
            JavaKISSMain.settingsFile = homeSettings;
            JavaKISSMain.settings = loadJSONObject(homeSettings);
        }
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }

            if (cmd.hasOption("f"))
            {
                JavaKISSMain.settingsFile = new File(cmd.getOptionValue("f"));
                JavaKISSMain.settings = loadJSONObject(settingsFile);
            }

            if (cmd.hasOption("t"))
            {
                settings.put("txTest", true);
                settings.put("txTestInterval", Long.valueOf(cmd.getOptionValue("t", "10")).longValue() * 1000l);
            }

            if (cmd.hasOption("a"))
            {
                settings.put("apiPort", Integer.valueOf(cmd.getOptionValue("a", "8101")).intValue());
            }

            if (cmd.hasOption("m"))
            {
                settings.put("payload", cmd.getOptionValue("m"));
            }

            if (cmd.hasOption("v"))
            {
                settings.put("verbose", true);
            }

            if (cmd.hasOption("h"))
            {
                settings.put("host", cmd.getOptionValue("h"));
            }

            if (cmd.hasOption("x"))
            {
                settings.put("postUrl", cmd.getOptionValue("x"));
            }

            if (cmd.hasOption("s"))
            {
                settings.put("source", cmd.getOptionValue("s"));
            }
            
            if (cmd.hasOption("d"))
            {
                settings.put("destination", cmd.getOptionValue("d"));
            }

            if (cmd.hasOption("l"))
            {
                settings.put("logPath", cmd.getOptionValue("l", "./jaxt-logs"));
            }

            if (cmd.hasOption("p"))
            {
                settings.put("port", Integer.valueOf(cmd.getOptionValue("p")).intValue());
            }
            if (JavaKISSMain.settings.has("logPath"))
            {
                JavaKISSMain.logsFolder = new File(JavaKISSMain.settings.optString("logPath", "./jaxt-logs"));
                if (!JavaKISSMain.logsFolder.exists())
                {
                    JavaKISSMain.logsFolder.mkdirs();
                }
            }
            KISSClient kClient = new KISSClient(settings.optString("host"), settings.optInt("port",8100));
            JavaKISSMain jkm = new JavaKISSMain(kClient);
            saveSettings();
            Runtime.getRuntime().addShutdownHook(new Thread() 
            { 
                public void run() 
                { 
                    saveSettings();
                }
            });
            kClient.connect();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "jaxt", "Java AX25 Tool: A Java KISS TNC Client implementation", options, "" );
        System.exit(0);
    }

    @Override
    public void onReceived(AX25Packet packet)
    {
        JSONObject logEntry = packet.toJSONObject();
        jsonLogAppend("rx.json", logEntry);
        jsonLogAppend(packet.getSourceCallsign() + ".json", logEntry);
        if (settings.optBoolean("verbose", false))
        {
            System.err.println("[" + this.simpleDateFormat.format(packet.getTimestamp()) + "] (Rx) " + packet.toLogString());
        }
        if (settings.has("postUrl"))
        {
            JavaKISSMain.postAX25Packet(settings.optString("postUrl"), packet);
        }
    }

    public static synchronized void logAppend(String filename, String text)
    {
        String pattern = "HH:mm:ss yyyy-MM-dd";
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
        String logText = "[" + simpleDateFormat.format(new Date(System.currentTimeMillis())) + "] " + text;
        if (JavaKISSMain.logsFolder != null)
        {        
            try
            {
                
                File logFile = new File(JavaKISSMain.logsFolder, filename);
                File logFileParent = logFile.getParentFile();
                if (!logFileParent.exists())
                    logFileParent.mkdirs();
                FileOutputStream logOutputStream = new FileOutputStream(logFile, true);;
                PrintWriter logWriter = new PrintWriter(logOutputStream, true, Charset.forName("UTF-8"));
                logWriter.println(logText);
                logWriter.flush();
                logWriter.close();
                logOutputStream.close();
            } catch (Exception e) {

            }
        }
        if (JavaKISSMain.settings.optBoolean("verbose", false))
                System.err.println(logText);
    }

    public static synchronized void jsonLogAppend(String filename, JSONObject object)
    {
        if (JavaKISSMain.logsFolder != null)
        {
            Date now = new Date(object.optLong("timestamp", (new Date(System.currentTimeMillis())).getTime()));
            String pattern = "yyyy-MM-dd HH:mm:ss";
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
            try
            {
                File logFile = new File(JavaKISSMain.logsFolder, filename);
                File logFileParent = logFile.getParentFile();
                if (!logFileParent.exists())
                    logFileParent.mkdirs();
                FileOutputStream logOutputStream = new FileOutputStream(logFile, true);;
                PrintWriter logWriter = new PrintWriter(logOutputStream, true, Charset.forName("UTF-8"));
                object.put("localTime", simpleDateFormat.format(now));
                logWriter.println(object.toString());
                logWriter.flush();
                logWriter.close();
                logOutputStream.close();
            } catch (Exception e) {
                log(e);
            }
        }
    }

    public static void log(Exception e)
    {
        if (JavaKISSMain.logsFolder != null)
        {
            try
            {
                String msg = e.getMessage();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos, true, Charset.forName("UTF-8"));
                e.printStackTrace(ps);
                ps.flush();

                String pattern = "HH:mm:ss yyyy-MM-dd";
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
                File logFile = new File(JavaKISSMain.logsFolder, "exceptions.log");
                File logFileParent = logFile.getParentFile();
                if (!logFileParent.exists())
                    logFileParent.mkdirs();
                FileOutputStream logOutputStream = new FileOutputStream(logFile, true);;
                PrintWriter logWriter = new PrintWriter(logOutputStream, true, Charset.forName("UTF-8"));
                String logText = "[" + simpleDateFormat.format(new Date(System.currentTimeMillis())) + "] " + msg + "\n" + baos.toString();
                logWriter.println(logText);
                logWriter.flush();
                logWriter.close();
                logOutputStream.close();
            } catch (Exception e2) {
            }
        }
    }

    public static void saveSettings()
    {
        if (JavaKISSMain.settings.has("txTest"))
            JavaKISSMain.settings.remove("txTest");
        if (JavaKISSMain.settingsFile != null)
            saveJSONObject(JavaKISSMain.settingsFile, JavaKISSMain.settings);
    }

    public static JSONObject loadJSONObject(File file)
    {
        try
        {
            FileInputStream fis = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            int ch;
            while((ch = fis.read()) != -1)
            {
                builder.append((char)ch);
            }
            fis.close();
            JSONObject props = new JSONObject(builder.toString());
            return props;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveJSONObject(File file, JSONObject obj)
    {
        try
        {
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            ps.print(obj.toString(2));
            ps.close();
            fos.close();
        } catch (Exception e) {
        }
    }

    public static void postAX25Packet(String url, AX25Packet packet)
    {
        try
        {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.getOutputStream().write(packet.toJSONObject().toString().getBytes());
            InputStream inputStream = con.getInputStream();
            int responseCode = con.getResponseCode();
            InputStreamReader isr = new InputStreamReader(inputStream);
            Optional<String> optResponse = new BufferedReader(isr).lines().reduce((a, b) -> a + b);
            String output = "NO OUTPUT";
            if (optResponse.isPresent())
                output = optResponse.get();
            JavaKISSMain.logAppend("main.log", "[POST " + String.valueOf(responseCode) + "] " + url + " - " + output);
        } catch (Exception e) {
            JavaKISSMain.logAppend("main.log", "[POST ERROR] " + url);
            log(e);
        }
    }

    @Override
    public void onKISSConnect(InetSocketAddress host)
    {
        JavaKISSMain.logAppend("main.log", "[KISS Connected] " + host.toString());
    }

    @Override
    public void onKISSDisconnect(InetSocketAddress host)
    {
        JavaKISSMain.logAppend("main.log", "[KISS Disconnected] " + host.toString());
    }
}
