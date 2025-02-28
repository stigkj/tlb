package tlb.server.repo;

import org.apache.log4j.Logger;
import tlb.TlbConstants;
import tlb.domain.TimeProvider;
import tlb.utils.FileUtil;
import tlb.utils.SystemEnvironment;
import org.apache.commons.io.FileUtils;

import java.io.*;


import static tlb.TlbConstants.Server.EntryRepoFactory.SUBSET_SIZE;
import static tlb.TlbConstants.Server.EntryRepoFactory.SUITE_RESULT;
import static tlb.TlbConstants.Server.EntryRepoFactory.SUITE_TIME;

/**
 * @understands creation of EntryRepo
 */
public class EntryRepoFactory implements Runnable {
    public static final String DELIMITER = "_";
    public static final String LATEST_VERSION = "LATEST";
    private static final Logger logger = Logger.getLogger(EntryRepoFactory.class.getName());

    //private final Map<String, EntryRepo> repos;
    private final String tlbStoreDir;
    private final TimeProvider timeProvider;
    private Cache<EntryRepo> cache;

    static interface Creator<T> {
        T create();
    }

    public EntryRepoFactory(SystemEnvironment env) {
        this(new File(env.val(TlbConstants.Server.TLB_DATA_DIR)), new TimeProvider());
    }

    EntryRepoFactory(File tlbStoreDir, TimeProvider timeProvider) {
        this.tlbStoreDir = tlbStoreDir.getAbsolutePath();
        this.cache = new Cache<EntryRepo>();
        this.timeProvider = timeProvider;
    }

    public void purge(String identifier) throws IOException {
        synchronized (repoId(identifier)) {
            cache.remove(identifier);
            File file = dumpFile(identifier);
            if (file.exists()) FileUtils.forceDelete(file);
        }
    }

    private String repoId(String identifier) {
        return identifier.intern();
    }

    public void purgeVersionsOlderThan(int versionLifeInDays) {
        for (String identifier : cache.keys()) {
            EntryRepo entryRepo = cache.get(identifier);
            if (entryRepo instanceof VersioningEntryRepo) {
                final VersioningEntryRepo repo = (VersioningEntryRepo) entryRepo;
                try {
                    repo.purgeOldVersions(versionLifeInDays);
                } catch (Exception e) {
                    logger.warn(String.format("failed to delete older versions for repo identified by '%s'", identifier), e);
                }
            }
        }
    }

    public SuiteResultRepo createSuiteResultRepo(final String namespace, final String version) throws ClassNotFoundException, IOException {
        return (SuiteResultRepo) findOrCreate(namespace, version, SUITE_RESULT, new Creator<SuiteResultRepo>() {
            public SuiteResultRepo create() {
                return new SuiteResultRepo();
            }
        });
    }

    public SuiteTimeRepo createSuiteTimeRepo(final String namespace, final String version) throws IOException {
        return (SuiteTimeRepo) findOrCreate(namespace, version, SUITE_TIME, new Creator<SuiteTimeRepo>() {
            public SuiteTimeRepo create() {
                return new SuiteTimeRepo(timeProvider);
            }
        });
    }

    public SubsetSizeRepo createSubsetRepo(final String namespace, final String version) throws IOException, ClassNotFoundException {
        return (SubsetSizeRepo) findOrCreate(namespace, version, SUBSET_SIZE, new Creator<SubsetSizeRepo>() {
            public SubsetSizeRepo create() {
                return new SubsetSizeRepo();
            }
        });
    }

    EntryRepo findOrCreate(String namespace, String version, String type, Creator<? extends EntryRepo> creator) throws IOException {
        String identifier = name(namespace, version, type);
        synchronized (repoId(identifier)) {
            EntryRepo repo = cache.get(identifier);
            if (repo == null) {
                repo = creator.create();
                repo.setNamespace(namespace);
                repo.setIdentifier(identifier);
                cache.put(identifier, repo);

                File diskDump = dumpFile(identifier);
                if (diskDump.exists()) {
                    final FileReader reader = new FileReader(diskDump);
                    repo.load(FileUtil.readIntoString(new BufferedReader(reader)));
                }
            }
            repo.setFactory(this);
            return repo;
        }
    }

    private File dumpFile(String identifier) {
        new File(tlbStoreDir).mkdirs();
        return new File(tlbStoreDir, identifier);
    }

    public static String name(String namespace, String version, String type) {
        return escape(namespace) + DELIMITER + escape(version) + DELIMITER + escape(type);
    }

    private static String escape(String str) {
        return str.replace(DELIMITER, DELIMITER + DELIMITER);
    }

    @Deprecated //for tests only
    Cache<EntryRepo> getRepos() {
        return cache;
    }

    public void run() {
        syncReposToDisk();
    }

    public void syncReposToDisk() {
        for (String identifier : cache.keys()) {
            FileWriter writer = null;
            try {
                //don't care about a couple entries not being persisted(at teardown), as client is capable of balancing on averages(treat like new suites)
                EntryRepo entryRepo = cache.get(identifier);
                if (entryRepo != null) {
                    synchronized (repoId(identifier)) {
                        entryRepo = cache.get(identifier);
                        if (entryRepo != null && entryRepo.isDirty()) {
                            writer = new FileWriter(dumpFile(identifier));
                            String dump = entryRepo.diskDump();
                            writer.write(dump);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn(String.format("disk dump of %s failed, tlb server may not be able to perform data dependent operations well on next reboot.", identifier), e);
            } finally {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (IOException e) {
                    logger.warn(String.format("closing of disk dump file of %s failed, tlb server may not be able to perform data dependent operations well on next reboot.", identifier), e);
                }
            }
        }
    }

    public void registerExitHook() {
        Runtime.getRuntime().addShutdownHook(exitHook());
    }

    public Thread exitHook() {
        return new Thread(this);
    }
}
