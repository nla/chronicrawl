package org.netpreserve.chronicrawl;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.List;

public class Chart {
    public static String chart(List<Database.Metric> metrics) throws XMLStreamException {
        for (var metric : metrics) {

        }

        StringWriter sw = new StringWriter();
        XMLStreamWriter xml = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(sw);
        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeStartElement("svg");
        xml.writeAttribute("xmlns", "http://www.w3.org/2000/svg");
        for (var metric : metrics) {
            xml.writeStartElement("rect");
            xml.writeAttribute("height", Long.toString(metric.bytes));
            xml.writeEndElement();
        }
        xml.writeEndElement();
        xml.writeEndDocument();
        return sw.toString();
    }
}
