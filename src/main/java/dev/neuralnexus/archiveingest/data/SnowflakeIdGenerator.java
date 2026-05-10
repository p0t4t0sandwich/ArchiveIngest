package dev.neuralnexus.archiveingest.data;

import com.spiderpig86.jayflake.SnowflakeGenerator;
import com.spiderpig86.jayflake.configuration.GeneratorConfiguration;
import com.spiderpig86.jayflake.configuration.SnowflakeConfiguration;
import com.spiderpig86.jayflake.lib.OverflowStrategy;
import com.spiderpig86.jayflake.time.DefaultTime;

import java.time.Clock;
import java.time.Instant;

public final class SnowflakeIdGenerator {

    private static final Instant EPOCH = Instant.ofEpochMilli(1706639400000L); // January 30, 2024 12:30:00 PM Central/Regina

    private static final SnowflakeGenerator GENERATOR = SnowflakeGenerator.create(
            SnowflakeConfiguration.builder()
                    .withTimestampBits(41)
                    .withDatacenterBits(0)
                    .withWorkerBits(10)
                    .withSequenceBits(12)
                    .build(),
            GeneratorConfiguration.builder()
                    .withDataCenter(0L)
                    .withWorker(0L)
                    .withOverflowStrategy(OverflowStrategy.SPIN_WAIT)
                    .build(),
            new DefaultTime(Clock.systemDefaultZone(), EPOCH)
    );

    private SnowflakeIdGenerator() {}

    public static String next() {
        return String.valueOf(GENERATOR.next().value());
    }
}
