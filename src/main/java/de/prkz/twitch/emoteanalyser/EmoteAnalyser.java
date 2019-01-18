package de.prkz.twitch.emoteanalyser;

import de.prkz.twitch.emoteanalyser.channel.ChannelStatsAggregation;
import de.prkz.twitch.emoteanalyser.emote.*;
import de.prkz.twitch.emoteanalyser.user.UserStatsAggregation;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

public class EmoteAnalyser {

	private static final Logger LOG = LoggerFactory.getLogger(EmoteAnalyser.class);

	private static final long CHECKPOINT_INTERVAL_MS = 60000;

	public static void main(String[] args) throws Exception {

		LOG.debug("test-debug");
		LOG.info("test-info");
		LOG.warn("test-warn");


		// Parse arguments
		if (args.length < 3) {
			System.out.println("Arguments: <jdbcUrl> <kafka-bootstrap-server> <aggregation-interval-millis>");
			System.exit(1);
		}

		String jdbcUrl = args[0];
		String kafkaBootstrapServer = args[1];
		Long aggregationIntervalMs = Long.parseLong(args[2]);

		// Prepare database
		Connection conn = DriverManager.getConnection(jdbcUrl);
		Statement stmt = conn.createStatement();


		// Create stream environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		// Synchronous checkpoints to local file system
		env.enableCheckpointing(CHECKPOINT_INTERVAL_MS);
		env.setStateBackend((StateBackend)new FsStateBackend("file:///data/checkpoints", false));

		env.setRestartStrategy(RestartStrategies.failureRateRestart(
				5, org.apache.flink.api.common.time.Time.minutes(1), Time.seconds(5)));

		// Pull messages from Kafka
		Properties kafkaProps = new Properties();
		kafkaProps.setProperty("bootstrap.servers", kafkaBootstrapServer);
		kafkaProps.setProperty("group.id", "twitch_chat_analyzer");
		kafkaProps.setProperty("auto.offset.reset", "earliest");

		FlinkKafkaConsumer011<Message> consumer = new FlinkKafkaConsumer011<>(
				"TwitchMessages", new MessageDeserializationSchema(), kafkaProps);
		DataStream<Message> messages = env
				.addSource(consumer)
				.name("KafkaSource")
				.assignTimestampsAndWatermarks(new Message.TimestampExtractor());

		// Per-Channel statistics
		ChannelStatsAggregation channelStatsAggregation =
				new ChannelStatsAggregation(jdbcUrl, aggregationIntervalMs);
		channelStatsAggregation.prepareTable(stmt);
		channelStatsAggregation.aggregateAndExportFrom(messages);

		// Per-User statistics
		UserStatsAggregation userStatsAggregation =
				new UserStatsAggregation(jdbcUrl, aggregationIntervalMs);
		userStatsAggregation.prepareTable(stmt);
		userStatsAggregation.aggregateAndExportFrom(messages);


		// Extract emotes from messages
		EmoteExtractor emoteExtractor = new EmoteExtractor(jdbcUrl);
		emoteExtractor.prepareTables(stmt);
		DataStream<Emote> emotes = messages
				.flatMap(emoteExtractor)
				.assignTimestampsAndWatermarks(new AscendingTimestampExtractor<Emote>() {
					@Override
					public long extractAscendingTimestamp(Emote emote) {
						return emote.timestamp;
					}
				})
				.name("ExtractEmotes");

		// Per-Emote statistics
		EmoteStatsAggregation emoteStatsAggregation =
				new EmoteStatsAggregation(jdbcUrl, aggregationIntervalMs);
		emoteStatsAggregation.prepareTable(stmt);
		emoteStatsAggregation.aggregateAndExportFrom(emotes);

		// Per-Emote per-User statistics
		UserEmoteStatsAggregation userEmoteStatsAggregation =
				new UserEmoteStatsAggregation(jdbcUrl, aggregationIntervalMs);
		userEmoteStatsAggregation.prepareTable(stmt);
		userEmoteStatsAggregation.aggregateAndExportFrom(emotes);


		stmt.close();
		conn.close();

		env.execute("EmoteAnalysis");
	}
}
