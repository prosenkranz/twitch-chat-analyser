package de.prkz.twitch.emoteanalyser;

import de.prkz.twitch.emoteanalyser.channel.ChannelStatsAggregation;
import de.prkz.twitch.emoteanalyser.emote.*;
import de.prkz.twitch.emoteanalyser.user.UserStatsAggregation;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

public class EmoteAnalyser {

	private static final Logger LOG = LoggerFactory.getLogger(EmoteAnalyser.class);
	private static String jdbcUrl;
	private static String[] channels;

	private static final Time AGGREGATION_INTERVAL = Time.minutes(1);

	public static void main(String[] args) throws Exception {

		LOG.debug("test-debug");
		LOG.info("test-info");
		LOG.warn("test-warn");


		// Parse arguments
		if (args.length < 2) {
			System.out.println("Arguments: <jdbcUrl> <channels...>");
			System.exit(1);
		}

		jdbcUrl = args[0];
		channels = new String[args.length - 1];
		for (int i = 1; i < args.length; ++i)
			channels[i - 1] = args[i];


		// Prepare database
		Connection conn = DriverManager.getConnection(jdbcUrl);
		Statement stmt = conn.createStatement();

		LOG.info("Preparing output table...");
		try {
			prepareEmotesTable(stmt);
		}
		catch (Exception ex) {
			LOG.error("Could not initialize emotes table", ex);
			System.exit(1);
		}


		// Create stream environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);


		// Twitch chat bot source
		DataStream<Message> messages = env
				.addSource(new TwitchSource(channels))
				.setParallelism(1) // only one bot!
				.name("TwitchSource")
				.assignTimestampsAndWatermarks(new Message.TimestampExtractor());


		// Per-Channel statistics
		ChannelStatsAggregation channelStatsAggregation = new ChannelStatsAggregation(jdbcUrl, AGGREGATION_INTERVAL);
		channelStatsAggregation.prepareTable(stmt);
		channelStatsAggregation.aggregateAndExportFrom(messages);

		// Per-User statistics
		UserStatsAggregation userStatsAggregation = new UserStatsAggregation(jdbcUrl, AGGREGATION_INTERVAL);
		userStatsAggregation.prepareTable(stmt);
		userStatsAggregation.aggregateAndExportFrom(messages);


		// Extract emotes from messages
		DataStream<Emote> emotes = messages
				.flatMap(new EmoteExtractor(jdbcUrl))
				.assignTimestampsAndWatermarks(new AscendingTimestampExtractor<Emote>() {
					@Override
					public long extractAscendingTimestamp(Emote emote) {
						return emote.timestamp;
					}
				})
				.name("ExtractEmotes");

		// Per-Emote statistics
		EmoteStatsAggregation emoteStatsAggregation = new EmoteStatsAggregation(jdbcUrl, AGGREGATION_INTERVAL);
		emoteStatsAggregation.prepareTable(stmt);
		emoteStatsAggregation.aggregateAndExportFrom(emotes);

		// Per-Emote per-User statistics
		UserEmoteStatsAggregation userEmoteStatsAggregation = new UserEmoteStatsAggregation(jdbcUrl, AGGREGATION_INTERVAL);
		userEmoteStatsAggregation.prepareTable(stmt);
		userEmoteStatsAggregation.aggregateAndExportFrom(emotes);


		stmt.close();
		conn.close();

		env.execute("EmoteAnalysis");
	}

	static void prepareEmotesTable(Statement stmt) throws SQLException {
		/*
			Emote type:
				0 - Twitch User
				1 - Twitch Global
				2 - BTTV
				3 - FFZ
				4 - Emoji
		 */
		stmt.execute("CREATE TABLE IF NOT EXISTS emotes(" +
				"emote VARCHAR(64) NOT NULL," +
				"type SMALLINT NOT NULL DEFAULT 0," +
				"PRIMARY KEY(emote))");

		ResultSet emoteCountResult = stmt.executeQuery("SELECT COUNT(emote) FROM emotes");
		emoteCountResult.next();
		if (emoteCountResult.getInt(1) == 0) {
			// Insert some default emotes, so we have something to track
			stmt.execute("INSERT INTO emotes(emote, type) VALUES('Kappa', 1), ('lirikN', 0), ('moon2S', 0);");
		}
	}
}