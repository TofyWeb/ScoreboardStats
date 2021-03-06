package com.github.games647.scoreboardstats.pvpstats;

import com.github.games647.scoreboardstats.BackwardsCompatibleUtil;
import com.github.games647.scoreboardstats.ScoreboardStats;
import com.github.games647.scoreboardstats.config.Lang;
import com.github.games647.scoreboardstats.config.Settings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

/**
 * This represents a handler for saving player stats.
 */
public class Database {

    private static final String METAKEY = "player_stats";

    private final ScoreboardStats plugin;

    private final Map<String, Integer> toplist = Maps.newHashMapWithExpectedSize(Settings.getTopitems());

    private final DatabaseConfiguration dbConfig;
    private HikariDataSource dataSource;

    public Database(ScoreboardStats plugin) {
        this.plugin = plugin;
        this.dbConfig = new DatabaseConfiguration(plugin);
    }

    /**
     * Get the cache player stats if they exists and the arguments are valid.
     *
     * @param request the associated player
     * @return the stats if they are in the cache
     */
    public PlayerStats getCachedStats(Player request) {
        if (request != null) {
            for (MetadataValue metadata : request.getMetadata(METAKEY)) {
                if (metadata.value() instanceof PlayerStats) {
                    return (PlayerStats) metadata.value();
                }
            }
        }

        return null;
    }

    /**
     * Starts loading the stats for a specific player in an external thread.
     *
     * @param player the associated player
     */
    public void loadAccountAsync(Player player) {
        if (getCachedStats(player) == null && dataSource != null) {
            Runnable statsLoader = new StatsLoader(plugin, dbConfig.isUuidUse(), player, this);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, statsLoader);
        }
    }

    /**
     * Starts loading the stats for a specific player sync
     *
     * @param uniqueId the associated playername or uuid
     * @return the loaded stats
     */
    public PlayerStats loadAccount(Object uniqueId) {
        if (uniqueId == null || dataSource == null) {
            return null;
        } else {
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet resultSet = null;
            try {
                conn = dataSource.getConnection();

                stmt = conn.prepareStatement("SELECT * FROM player_stats WHERE "
                        + (dbConfig.isUuidUse() ? "uuid" : "playername")
                        + "=?");
                stmt.setString(1, uniqueId.toString());

                resultSet = stmt.executeQuery();
                return extractPlayerStats(resultSet);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Error loading player profile", ex);
            } finally {
                close(resultSet);
                close(stmt);
                close(conn);
            }

            return null;
        }
    }

    private PlayerStats extractPlayerStats(ResultSet resultSet) throws SQLException {
        if (resultSet.next()) {
            int id = resultSet.getInt(1);

            String unparsedUUID = resultSet.getString(2);
            UUID uuid = null;
            if (unparsedUUID != null) {
                uuid = UUID.fromString(unparsedUUID);
            }

            String playerName = resultSet.getString(3);

            int kills = resultSet.getInt(4);
            int deaths = resultSet.getInt(5);
            int mobkills = resultSet.getInt(6);
            int killstreak = resultSet.getInt(7);

            long lastOnline = resultSet.getLong(8);
            return new PlayerStats(id, uuid, playerName, kills, deaths, mobkills, killstreak, lastOnline);
        } else {
            //If there are no existing stat create a new object with empty stats
            return new PlayerStats();
        }
    }

    /**
     * Starts loading the stats for a specific player sync
     *
     * @param player the associated player
     * @return the loaded stats
     */
    public PlayerStats loadAccount(Player player) {
        if (player == null || dataSource == null) {
            return null;
        } else {
            if (dbConfig.isUuidUse()) {
                return loadAccount(player.getUniqueId());
            } else {
                return loadAccount(player.getName());
            }
        }
    }

    /**
     * Save PlayerStats async.
     *
     * @param stats PlayerStats data
     */
    public void saveAsync(PlayerStats stats) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> save(Lists.newArrayList(stats)));
    }

    /**
     * Save the PlayerStats on the current Thread.
     *
     * @param stats PlayerStats data
     */
    public void save(List<PlayerStats> stats) {
        if (stats != null && dataSource != null) {
            update(stats.stream()
                    .filter(Objects::nonNull)
                    .filter(PlayerStats::isModified)
                    .filter(stat -> !stat.isNew())
                    .collect(Collectors.toList()));

            insert(stats.stream()
                    .filter(Objects::nonNull)
                    .filter(PlayerStats::isModified)
                    .filter(PlayerStats::isNew)
                    .collect(Collectors.toList()));
        }
    }

    private void update(List<PlayerStats> stats) {
        //Save the stats to the database
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            stmt = conn.prepareStatement("UPDATE player_stats "
                    + "SET kills=?, deaths=?, killstreak=?, mobkills=?, last_online=?, playername=? "
                    + "WHERE id=?");

            for (PlayerStats stat : stats) {
                stmt.setInt(1, stat.getKills());
                stmt.setInt(2, stat.getDeaths());
                stmt.setInt(3, stat.getKillstreak());
                stmt.setInt(4, stat.getMobkills());

                stmt.setLong(5, stat.getLastOnline());
                stmt.setString(6, stat.getPlayername());

                stmt.setInt(7, stat.getId());
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Error updating profiles", ex);
        } finally {
            close(stmt);
            close(conn);
        }
    }

    private void insert(List<PlayerStats> stats) {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet generatedKeys = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            stmt = conn.prepareStatement("INSERT INTO player_stats "
                    + "(uuid, playername, kills, deaths, killstreak, mobkills, last_online) VALUES "
                    + "(?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);

            for (PlayerStats stat : stats) {
                stmt.setString(1, stat.getUuid() == null ? null : stat.getUuid().toString());
                stmt.setString(2, stat.getPlayername());

                stmt.setInt(3, stat.getKills());
                stmt.setInt(4, stat.getDeaths());
                stmt.setInt(5, stat.getKillstreak());
                stmt.setInt(6, stat.getMobkills());

                stmt.setLong(7, stat.getLastOnline());
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

            generatedKeys = stmt.getGeneratedKeys();
            for (PlayerStats stat : stats) {
                if (!generatedKeys.next()) {
                    break;
                }

                stat.setId(generatedKeys.getInt(1));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Error inserting profiles", ex);
        } finally {
            close(generatedKeys);
            close(stmt);
            close(conn);
        }
    }

    /**
     * Starts saving all cache player stats and then clears the cache.
     */
    public void saveAll() {
        try {
            plugin.getLogger().info(Lang.get("savingStats"));

            //If pvpstats are enabled save all stats that are in the cache
            List<PlayerStats> toSave = BackwardsCompatibleUtil.getOnlinePlayers().stream()
                    .map(this::getCachedStats)
                    .filter(Objects::nonNull)
                    .filter(PlayerStats::isModified)
                    .collect(Collectors.toList());

            if (!toSave.isEmpty()) {
                save(toSave);
            }

            dataSource.close();
        } finally {
            //Make rally sure we remove all even on error
            BackwardsCompatibleUtil.getOnlinePlayers()
                    .forEach(player -> player.removeMetadata(METAKEY, plugin));
        }
    }

    /**
     * Initialize a components and checking for an existing database
     */
    public void setupDatabase() {
        //Check if pvpstats should be enabled
        dbConfig.loadConfiguration();

        dataSource = new HikariDataSource(dbConfig.getServerConfig());

        Connection conn = null;
        Statement stmt = null;
        try {
            conn = dataSource.getConnection();
            stmt = conn.createStatement();
            String createTableQuery = "CREATE TABLE IF NOT EXISTS player_stats ( "
                    + "id integer PRIMARY KEY AUTO_INCREMENT, "
                    + "uuid varchar(40), "
                    + "playername varchar(16) not null, "
                    + "kills integer not null, "
                    + "deaths integer not null, "
                    + "mobkills integer not null, "
                    + "killstreak integer not null, "
                    + "last_online timestamp not null )";

            if (dbConfig.getServerConfig().getDriverClassName().contains("sqlite")) {
                createTableQuery = createTableQuery.replace("AUTO_INCREMENT", "");
            }

            stmt.execute(createTableQuery);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Error creating database ", ex);
        } finally {
            close(stmt);
            close(conn);
        }

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::updateTopList, 20 * 60 * 5, 0);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dataSource == null) {
                return;
            }

            Future<Collection<? extends Player>> syncPlayers = Bukkit.getScheduler()
                    .callSyncMethod(plugin, BackwardsCompatibleUtil::getOnlinePlayers);

            try {
                Collection<? extends Player> onlinePlayers = syncPlayers.get();

                List<PlayerStats> toSave = onlinePlayers.stream()
                        .map(this::getCachedStats)
                        .filter(Objects::nonNull)
                        .filter(PlayerStats::isModified)
                        .collect(Collectors.toList());

                if (!toSave.isEmpty()) {
                    save(toSave);
                }
            } catch (CancellationException cancelEx) {
                //ignore it on shutdown
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, null, ex);
            }
        }, 20 * 60, 0);

        registerEvents();
    }

    /**
     * Get the a map of the best players for a specific category.
     *
     * @return a iterable of the entries
     */
    public Collection<Map.Entry<String, Integer>> getTop() {
        synchronized (toplist) {
            return toplist.entrySet();
        }
    }

    /**
     * Updates the toplist
     */
    public void updateTopList() {
        String type = Settings.getTopType();
        Map<String, Integer> newToplist;
        switch (type) {
            case "killstreak":
                newToplist = getTopList("killstreak", PlayerStats::getKillstreak);
                break;
            case "mob":
                newToplist = getTopList("mobkills", PlayerStats::getMobkills);
                break;
            default:
                newToplist = getTopList("kills", PlayerStats::getKills);
                break;
        }

        synchronized (toplist) {
            //set it after fetching so it's only blocking for a short time
            toplist.clear();
            toplist.putAll(newToplist);
        }
    }

    private Map<String, Integer> getTopList(String type, Function<PlayerStats, Integer> valueMapper) {
        if (dataSource != null) {
            Connection conn = null;
            Statement stmt = null;
            ResultSet resultSet = null;
            try {
                conn = dataSource.getConnection();
                stmt = conn.createStatement();

                resultSet = stmt.executeQuery("SELECT * FROM player_stats "
                        + "ORDER BY " + type + " desc "
                        + "LIMIT " + Settings.getTopitems());

                List<PlayerStats> result = Lists.newArrayList();
                for (int i = 0; i < Settings.getTopitems(); i++) {
                    PlayerStats stats = extractPlayerStats(resultSet);
                    if (!stats.isNew()) {
                        result.add(stats);
                    }
                }

                return result.stream().collect(Collectors.toMap(PlayerStats::getPlayername, valueMapper));
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Error loading top list", ex);
            } finally {
                close(resultSet);
                close(stmt);
                close(conn);
            }
        }

        return Collections.emptyMap();
    }

    private void registerEvents() {
        if (Bukkit.getPluginManager().isPluginEnabled("InSigns")) {
            //Register this listerner if InSigns is available
            new SignListener(plugin, "[Kill]", this);
            new SignListener(plugin, "[Death]", this);
            new SignListener(plugin, "[KDR]", this);
            new SignListener(plugin, "[Streak]", this);
            new SignListener(plugin, "[Mob]", this);
        }

        plugin.getReplaceManager().register(new StatsVariables(plugin, this));
        Bukkit.getPluginManager().registerEvents(new StatsListener(plugin, this), plugin);
    }

    private void close(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ex) {
                //ignore
            }
        }
    }
}
