package com.internetitem.logback.elasticsearch;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Context;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.internetitem.logback.elasticsearch.config.ElasticsearchProperties;
import com.internetitem.logback.elasticsearch.config.Property;
import com.internetitem.logback.elasticsearch.config.Settings;
import com.internetitem.logback.elasticsearch.util.AbstractPropertyAndEncoder;
import com.internetitem.logback.elasticsearch.util.ErrorReporter;
import com.internetitem.logback.elasticsearch.writer.ElasticsearchWriter;
import com.internetitem.logback.elasticsearch.writer.LoggerWriter;
import com.internetitem.logback.elasticsearch.writer.StdErrWriter;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractElasticsearchPublisher<T> implements Runnable {

	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
	private static final String MDC_PROPERTY_NAME = "mdc";
	private static final String ARGS_PROPERTY_NAME = "args";
	public static final String THREAD_NAME_PREFIX = "es-writer-";

	private volatile List<T> events;
	private ElasticsearchOutputAggregator outputAggregator;
	private List<AbstractPropertyAndEncoder<T>> propertyList;

	private AbstractPropertyAndEncoder<T> indexPattern;
	private JsonFactory jf;
	private JsonGenerator jsonGenerator;

	private ErrorReporter errorReporter;
	private Settings settings;

	private final Object lock;

	private volatile boolean working;


	public AbstractElasticsearchPublisher(Context context, ErrorReporter errorReporter, Settings settings, ElasticsearchProperties properties) throws IOException {
		this.errorReporter = errorReporter;
		this.events = new ArrayList<T>();
		this.lock = new Object();
		this.settings = settings;

		this.outputAggregator = configureOutputAggregator(settings, errorReporter);

		this.jf = new JsonFactory();
		this.jf.setRootValueSeparator(null);
		this.jsonGenerator = jf.createGenerator(outputAggregator);

		this.indexPattern = buildPropertyAndEncoder(context, new Property("<index>", settings.getIndex(), false));
		this.propertyList = generatePropertyList(context, properties);
	}

	private static ElasticsearchOutputAggregator configureOutputAggregator(Settings settings, ErrorReporter errorReporter)  {
		ElasticsearchOutputAggregator spigot = new ElasticsearchOutputAggregator(settings, errorReporter);

		if (settings.isLogsToStderr()) {
			spigot.addWriter(new StdErrWriter());
		}

		if (settings.getLoggerName() != null) {
			spigot.addWriter(new LoggerWriter(settings.getLoggerName()));
		}

		if (settings.getUrl() != null) {
			spigot.addWriter(new ElasticsearchWriter(errorReporter, settings));
		}

		return spigot;
	}

	private List<AbstractPropertyAndEncoder<T>> generatePropertyList(Context context, ElasticsearchProperties properties) {
		List<AbstractPropertyAndEncoder<T>> list = new ArrayList<AbstractPropertyAndEncoder<T>>();
		if (properties != null) {
			for (Property property : properties.getProperties()) {
				list.add(buildPropertyAndEncoder(context, property));
			}
		}
		return list;
	}

	protected abstract AbstractPropertyAndEncoder<T> buildPropertyAndEncoder(Context context, Property property);

	public void addEvent(T event) {
		if (!outputAggregator.hasOutputs()) {
			return;
		}

		synchronized (lock) {
			events.add(event);
			if (!working) {
				working = true;
				Thread thread = new Thread(this, THREAD_NAME_PREFIX + THREAD_COUNTER.getAndIncrement());
				thread.start();
			}
		}
	}

	public void run() {
		int currentTry = 1;
		int maxRetries = settings.getMaxRetries();
		while (true) {
			try {
				Thread.sleep(settings.getSleepTime());

				List<T> eventsCopy = null;
				synchronized (lock) {
					if (!events.isEmpty()) {
						eventsCopy = events;
						events = new ArrayList<T>();
						currentTry = 1;
					}

					if (eventsCopy == null) {
						if (!outputAggregator.hasPendingData()) {
							// all done
							working = false;
							return;
						} else {
							// Nothing new, must be a retry
							if (currentTry > maxRetries) {
								// Oh well, better luck next time
								working = false;
								return;
							}
						}
					}
				}

				if (eventsCopy != null) {
					serializeEvents(jsonGenerator, eventsCopy, propertyList);
				}

				if (!outputAggregator.sendData()) {
					currentTry++;
				}
			} catch (Exception e) {
				errorReporter.logError("Internal error handling log data: " + e.getMessage(), e);
				currentTry++;
			}
		}
	}


	private void serializeEvents(JsonGenerator gen, List<T> eventsCopy, List<AbstractPropertyAndEncoder<T>> propertyList) throws IOException {
		for (T event : eventsCopy) {
			serializeIndexString(gen, event);
			gen.writeRaw('\n');
			serializeEvent(gen, event, propertyList);
			gen.writeRaw('\n');
		}
		gen.flush();
	}

	private void serializeIndexString(JsonGenerator gen, T event) throws IOException {
		gen.writeStartObject();
			gen.writeObjectFieldStart("index");
				gen.writeObjectField("_index", indexPattern.encode(event));
				String type = settings.getType();
				if (type != null) {
					gen.writeObjectField("_type", type);
				}
			gen.writeEndObject();
		gen.writeEndObject();
	}

	private void serializeEvent(JsonGenerator gen, T event, List<AbstractPropertyAndEncoder<T>> propertyList) throws IOException {
		gen.writeStartObject();

		serializeCommonFields(gen, event);

		for (AbstractPropertyAndEncoder<T> pae : propertyList) {
			String name = pae.getName();

			if (MDC_PROPERTY_NAME.equalsIgnoreCase(name)) {
				this.splitMDCFields(event, gen);
			} else if (ARGS_PROPERTY_NAME.equalsIgnoreCase(name)) {
				this.writeArgs(event, gen);
			} else {
				String value = pae.encode(event);
				if (pae.allowEmpty() || (value != null && !value.isEmpty())) {
					gen.writeObjectField(pae.getName(), value);
				}
			}
		}
		gen.writeEndObject();
	}

	@SuppressWarnings("unchecked")
	private void writeArgs(T event, JsonGenerator gen) throws IOException {
		ILoggingEvent logEvent = (ILoggingEvent) event;
		if (event != null) {
			Object[] args = logEvent.getArgumentArray();
			if (args != null) {
				for (Object object : args) {
					if (object != null && object instanceof Map) {
						try {
							Map map = (Map) object;
							this.writeMap(map, gen);
						} catch (ClassCastException ex) {
							gen.writeStringField("error", ex.getMessage());
						}
					}
				}
			}
		}
	}


	private void splitMDCFields(T event, JsonGenerator gen) throws IOException {
		ILoggingEvent logEvent = (ILoggingEvent) event;
		Map<String, String> mdcPropertyMap = logEvent.getMDCPropertyMap();
		this.writeMap(mdcPropertyMap, gen);
	}

	private void writeMap(Map<String, String> map, JsonGenerator gen) throws IOException {
		for (Map.Entry<String, String> entry : map.entrySet()) {
			gen.writeStringField(entry.getKey(), entry.getValue());
		}
	}

	protected abstract void serializeCommonFields(JsonGenerator gen, T event) throws IOException;

	protected static String getTimestamp(long timestamp) {
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(timestamp);
		return DatatypeConverter.printDateTime(cal);
	}

}
