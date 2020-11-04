package oryanmoshe.kafka.connect.util;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.kafka.connect.data.SchemaBuilder;

public class TimestampConverter implements CustomConverter<SchemaBuilder, RelationalColumn> {

    private static final Map<String, String> MONTH_MAP = Map.ofEntries(Map.entry("jan", "01"), Map.entry("feb", "02"),
            Map.entry("mar", "03"), Map.entry("apr", "04"), Map.entry("may", "05"), Map.entry("jun", "06"),
            Map.entry("jul", "07"), Map.entry("aug", "08"), Map.entry("sep", "09"), Map.entry("oct", "10"),
            Map.entry("nov", "11"), Map.entry("dec", "12"));
    public static final int MILLIS_LENGTH = 13;

    public static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    public static final String DEFAULT_TIME_FORMAT = "HH:mm:ss.SSS";
    public static final String DEFAULT_TIME_ZONE   = "UTC";

    public static final List<String> SUPPORTED_DATA_TYPES = List.of("date", "time", "datetime", "timestamp",
								    "timestamptz", "datetime2");

    private static final String DATETIME_REGEX = "(?<datetime>(?<date>(?:(?<year>\\d{4})-(?<month>\\d{1,2})-(?<day>\\d{1,2}))|(?:(?<day2>\\d{1,2})\\/(?<month2>\\d{1,2})\\/(?<year2>\\d{4}))|(?:(?<day3>\\d{1,2})-(?<month3>\\w{3})-(?<year3>\\d{4})))?(?:\\s?T?(?<time>(?<hour>\\d{1,2}):(?<minute>\\d{1,2}):(?<second>\\d{1,2})\\.?(?<milli>\\d{0,7})?)?))";
    private static final Pattern regexPattern = Pattern.compile(DATETIME_REGEX);

    public String strDatetimeFormat, strDateFormat, strTimeFormat;
    public TimeZone tz;
    public Boolean debug;

    private SchemaBuilder datetimeSchema = SchemaBuilder.string().optional().name("oryanmoshe.time.DateTimeString");

    private SimpleDateFormat simpleDatetimeFormatter, simpleDateFormatter, simpleTimeFormatter;

    private Logger logger = LoggerFactory.getLogger(TimestampConverter.class);

    private void logAvailableTimeZones() {

	String tzids[] = TimeZone.getAvailableIDs();

	for (int i = 0; i < tzids.length; i++) {
	    logger.info("[TimestampConverter.logAvailableTimeZones] timezone id {}={}", i, tzids[i]);
	}

    }

    @Override
    public void configure(Properties props) {

        this.debug = props.getProperty("debug", "false").equals("true");

	this.strDatetimeFormat = props.getProperty("format.datetime", DEFAULT_DATETIME_FORMAT);
        this.simpleDatetimeFormatter = new SimpleDateFormat(this.strDatetimeFormat);

        this.strDateFormat = props.getProperty("format.date", DEFAULT_DATE_FORMAT);
        this.simpleDateFormatter = new SimpleDateFormat(this.strDateFormat);

        this.strTimeFormat = props.getProperty("format.time", DEFAULT_TIME_FORMAT);
        this.simpleTimeFormatter = new SimpleDateFormat(this.strTimeFormat);

	if (this.debug)
	    logAvailableTimeZones();

	this.tz = TimeZone.getTimeZone(props.getProperty("format.timezone", DEFAULT_TIME_ZONE));

	if (this.tz == null) {
	    logger.warn("[TimestampConverter] no timezone specified, defaulting to {}", DEFAULT_TIME_ZONE);
	    this.tz = TimeZone.getTimeZone(DEFAULT_TIME_ZONE);
	}

        this.simpleDatetimeFormatter.setTimeZone(this.tz);
        this.simpleTimeFormatter.setTimeZone(this.tz);

        if (this.debug)
	    logger.info("[TimestampConverter.configure] Finished configuring formats. strDatetimeFormat: {}, "
			+ "strTimeFormat: {} strDateFormat: {}, timezone: {}",
			this.strDatetimeFormat, this.strTimeFormat, this.strDateFormat, this.tz.getID());

    }

    @Override
    public void converterFor(RelationalColumn column, ConverterRegistration<SchemaBuilder> registration) {

        if (this.debug)
	    logger.info("[TimestampConverter.converterFor] Starting to register column. column.name: {}, column.typeName: {}",
			column.name(), column.typeName());
        if (SUPPORTED_DATA_TYPES.stream().anyMatch(s -> s.equalsIgnoreCase(column.typeName()))) {
            boolean isTime = "time".equalsIgnoreCase(column.typeName());
            registration.register(datetimeSchema, rawValue -> {
                if (rawValue == null)
                    return rawValue;

		if (this.debug)
		    logger.info("[TimestampConverter.converterFor] Raw Value: {}",
				rawValue);

		Long millis = getMillis(rawValue.toString(), isTime);
                if (millis == null)
                    return rawValue.toString();

                Instant instant = Instant.ofEpochMilli(millis);
                Date dateObject = Date.from(instant);
		String convertedValue = "";
		
                if (this.debug)
		    logger.info("[TimestampConverter.converterFor] Before returning conversion. column.name: {}, column.typeName: {}, millis: {}",
				column.name(), column.typeName(), millis);
                switch (column.typeName().toLowerCase()) {
                    case "time":
                        convertedValue = this.simpleTimeFormatter.format(dateObject);
			break;
                    case "date":
                        convertedValue = this.simpleDateFormatter.format(dateObject);
			break;
                    default:
                        convertedValue = this.simpleDatetimeFormatter.format(dateObject);
			break;
                }

		if (this.debug)
		    logger.info("[TimestampConverter.converterFor] After conversion. column.name: {}, column.typeName: {}, convertedValue: {}",
				column.name(), column.typeName(), convertedValue);

		return convertedValue;
            });
        }
    }

    private Long getMillis(String timestamp, boolean isTime) {

        if (timestamp.isBlank())
            return null;

        if (timestamp.contains(":") || timestamp.contains("-")) {
            return milliFromDateString(timestamp);
        }

        int excessLength = timestamp.length() - MILLIS_LENGTH;
        long longTimestamp = Long.parseLong(timestamp);

        if (isTime)
            return longTimestamp;

        if (excessLength < 0)
            return longTimestamp * 24 * 60 * 60 * 1000;

        long millis = longTimestamp / (long) Math.pow(10, excessLength);
        return millis;

    }

    private Long milliFromDateString(String timestamp) {

        Matcher matches = regexPattern.matcher(timestamp);

        if (matches.find()) {
            String year = (matches.group("year") != null ? matches.group("year")
                    : (matches.group("year2") != null ? matches.group("year2") : matches.group("year3")));
            String month = (matches.group("month") != null ? matches.group("month")
                    : (matches.group("month2") != null ? matches.group("month2") : matches.group("month3")));
            String day = (matches.group("day") != null ? matches.group("day")
                    : (matches.group("day2") != null ? matches.group("day2") : matches.group("day3")));
            String hour = matches.group("hour") != null ? matches.group("hour") : "00";
            String minute = matches.group("minute") != null ? matches.group("minute") : "00";
            String second = matches.group("second") != null ? matches.group("second") : "00";
            String milli = matches.group("milli") != null ? matches.group("milli") : "000";

            if (milli.length() > 3)
                milli = milli.substring(0, 3);

            String dateStr = "";
            dateStr += String.format("%s:%s:%s.%s", ("00".substring(hour.length()) + hour),
                    ("00".substring(minute.length()) + minute), ("00".substring(second.length()) + second),
                    (milli + "000".substring(milli.length())));

            if (year != null) {
                if (month.length() > 2)
                    month = MONTH_MAP.get(month.toLowerCase());

                dateStr = String.format("%s-%s-%sT%sZ", year, ("00".substring(month.length()) + month),
                        ("00".substring(day.length()) + day), dateStr);
            } else {
                dateStr = String.format("%s-%s-%sT%sZ", "2020", "01", "01", dateStr);
            }

	    if (this.debug)
		logger.info("[TimestampConverter.milliFromDateString] decoded dateStr = {}", dateStr);
	    
            Date dateObj = Date.from(Instant.parse(dateStr));
            return dateObj.getTime();

        }

        return null;
    }

}
