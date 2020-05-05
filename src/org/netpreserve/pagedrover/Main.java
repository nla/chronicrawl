package org.netpreserve.pagedrover;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException {
        List<String> seeds = new ArrayList<>();
        boolean initDb = false;
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c":
                    config.load(Paths.get(args[++i]));
                    break;
                case "-h":
                case "--help":
                    System.out.println("Usage: trickler [-c configfile]");
                    System.out.println("");
                    System.out.println("-c, --config FILE  Load config from properties file");
                    System.out.println("-h, --help         Print this help");
                    System.out.println("    --init         (Re-)initialize database (DELETES EXISTING DATA)");
                    System.out.println("    --version      Print version number");
                    System.exit(0);
                    break;
                case "--init":
                    initDb = true;
                    break;
                case "--version":
                    System.out.println(Config.version());
                    System.exit(0);
                    break;
                default:
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        System.exit(1);
                    }
                    seeds.add(args[i]);
            }
        }
        config.load(System.getenv());
        config.load(System.getProperties());
        Database db = new Database();
        if (initDb) db.init();
        try (Crawl crawl = new Crawl(config, db);
             Pywb pywb = new Pywb(config);
             Webapp webapp = new Webapp(crawl, crawl.config.uiPort)) {
            // finally block sometimes doesn't get called so use a shutdown hook too
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { crawl.close(); } catch (Exception e) {}
                    try { pywb.close(); } catch (Exception e) {}
            }));

            seeds.forEach(crawl::addSeed);
            crawl.run();
        }
    }
}
