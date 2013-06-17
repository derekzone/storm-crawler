package com.digitalpebble.storm.crawler.bolt.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.Link;
import org.apache.tika.sax.LinkContentHandler;
import org.apache.tika.sax.TeeContentHandler;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;

import com.digitalpebble.storm.crawler.StormConfiguration;
import com.digitalpebble.storm.crawler.util.Configuration;

/**
 * Uses Tika to parse the output of a fetch and extract text + metadata
 ***/

@SuppressWarnings("serial")
public class ParserBolt extends BaseRichBolt {

    private Configuration config;

    private Tika tika;

    private OutputCollector collector;

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(ParserBolt.class);

    public void prepare(Map conf, TopologyContext context,
            OutputCollector collector) {
        config = StormConfiguration.create();

        // instanciate Tika
        long start = System.currentTimeMillis();
        tika = new Tika();
        long end = System.currentTimeMillis();

        LOG.debug("Tika loaded in " + (end - start) + " msec");

        this.collector = collector;
    }

    public void execute(Tuple tuple) {
        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");
        HashMap<String, String> metadata = (HashMap<String, String>) tuple
                .getValueByField("metadata");

        // TODO check status etc...

        long start = System.currentTimeMillis();

        // rely on mime-type provided by server or guess?

        ByteArrayInputStream bais = new ByteArrayInputStream(content);
        Metadata md = new Metadata();

        String text = null;

        LinkContentHandler linkHandler = new LinkContentHandler();
        ContentHandler textHandler = new BodyContentHandler();
        TeeContentHandler teeHandler = new TeeContentHandler(linkHandler, textHandler);
        ParseContext parseContext = new ParseContext();
        // parse
        try {
            tika.getParser().parse(bais, teeHandler, md, parseContext);
            text = textHandler.toString();
        } catch (Exception e) {
            LOG.error("Exception while parsing "+url, e.getMessage());
            collector.fail(tuple);
        } finally {
            try {
                bais.close();
            } catch (IOException e) {
                LOG.error("Exception while closing stream", e);
            }
        }
        
        long end = System.currentTimeMillis();

        LOG.debug("Parsed " + url + "in " + (end - start) + " msec");

        // get the outlinks and convert them to strings (for now)

        List<Link> links = linkHandler.getLinks();
        List<String> slinks = new ArrayList<String>(links.size());
        for (Link l:links){
            slinks.add(l.getUri());
        }

        // add parse md to metadata
        for (String k : md.names()) {
            // TODO handle mutliple values
            String value = md.get(k);
            metadata.put("parse." + k, value);
        }

        // generate output
        List<Object> fields = new ArrayList<Object>(4);
        fields.add(url);
        fields.add(content);
        fields.add(metadata);
        fields.add(text.trim());
        fields.add(slinks);

        collector.emit(fields);
        collector.ack(tuple);

    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // output of this module is the list of fields to index
        // with at least the URL, text content

        declarer.declare(new Fields("url", "content", "metadata", "text", "outlinks"));
    }

}