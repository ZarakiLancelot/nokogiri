/**
 * (The MIT License)
 *
 * Copyright (c) 2008 - 2011:
 *
 * * {Aaron Patterson}[http://tenderlovemaking.com]
 * * {Mike Dalessio}[http://mike.daless.io]
 * * {Charles Nutter}[http://blog.headius.com]
 * * {Sergio Arbeo}[http://www.serabe.com]
 * * {Patrick Mahoney}[http://polycrystal.org]
 * * {Yoko Harada}[http://yokolet.blogspot.com]
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * 'Software'), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package nokogiri.internals;

import static nokogiri.internals.NokogiriHelpers.getLocalPart;
import static nokogiri.internals.NokogiriHelpers.getPrefix;
import static nokogiri.internals.NokogiriHelpers.isNamespace;
import static nokogiri.internals.NokogiriHelpers.stringOrNil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import nokogiri.XmlSyntaxError;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyObject;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.util.RuntimeHelpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

/**
 * A handler for SAX parsing.
 *
 * @author sergio
 * @author Yoko Harada <yokolet@gmail.com>
 */
public class NokogiriHandler extends DefaultHandler2 implements XmlDeclHandler {
    StringBuilder charactersBuilder;
    private final Ruby ruby;
    private final RubyClass attrClass;
    private final IRubyObject object;

    /**
     * Stores parse errors with the most-recent error last.
     *
     * TODO: should these be stored in the document 'errors' array?
     * Currently only string messages are stored there.
     */
    private final LinkedList<RaiseException> errors = new LinkedList<RaiseException>();

    private Locator locator;
    private static String htmlParserName = "Nokogiri::HTML::SAX::Parser";
    private boolean needEmptyAttrCheck = false;

    public NokogiriHandler(Ruby runtime, IRubyObject object) {
        this.ruby = runtime;
        this.attrClass = (RubyClass) runtime.getClassFromPath("Nokogiri::XML::SAX::Parser::Attribute");
        this.object = object;
        String objectName = object.getMetaClass().getName();
        if (htmlParserName.equals(objectName)) needEmptyAttrCheck = true;
    }

    @Override
    public void skippedEntity(String skippedEntity) {
        call("error", ruby.newString("Entity '" + skippedEntity + "' not defined\n"));
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    @Override
    public void startDocument() {
        call("start_document");
        charactersBuilder = new StringBuilder();
    }

    @Override
    public void xmlDecl(String version, String encoding, String standalone) {
        call("xmldecl", stringOrNil(ruby, version),
             stringOrNil(ruby, encoding),
             stringOrNil(ruby, standalone));
    }

    @Override
    public void endDocument() {
        populateCharacters();
        call("end_document");
    }

    @Override
    public void processingInstruction(String target, String data) {
      call("processing_instruction", ruby.newString(target), ruby.newString(data));
    }

    /*
     * This calls "start_element_namespace".
     *
     * Attributes that define namespaces are passed in a separate
     * array of <code>[:prefix, :uri]</code> arrays and are not
     * passed with the other attributes.
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) throws SAXException {
        // for attributes other than namespace attrs
        RubyArray rubyAttr = RubyArray.newArray(ruby);
        // for namespace defining attributes
        RubyArray rubyNSAttr = RubyArray.newArray(ruby);

        ThreadContext context = ruby.getCurrentContext();
        boolean fromFragmentHandler = false; // isFromFragmentHandler();

        for (int i = 0; i < attrs.getLength(); i++) {
            String u = attrs.getURI(i);
            String qn = attrs.getQName(i);
            String ln = attrs.getLocalName(i);
            String val = attrs.getValue(i);
            String pre;

            pre = getPrefix(qn);
            if (ln == null || ln.isEmpty()) ln = getLocalPart(qn);

            if (isNamespace(qn) && !fromFragmentHandler) {
                // I haven't figured the reason out yet, but, in somewhere,
                // namespace is converted to array in array and cause
                // TypeError at line 45 in fragment_handler.rb
                RubyArray ns = RubyArray.newArray(ruby, 2);
                if (ln.equals("xmlns")) ln = null;
                ns.add(stringOrNil(ruby, ln));
                ns.add(ruby.newString(val));
                rubyNSAttr.add(ns);
            } else {
                IRubyObject[] args = null;
                if (needEmptyAttrCheck) {
                    if (isEmptyAttr(ln)) {
                        args = new IRubyObject[] {
                            stringOrNil(ruby, ln), stringOrNil(ruby, pre), stringOrNil(ruby, u)
                        };
                    }
                }
                if (args == null) {
                    args = new IRubyObject[] {
                        stringOrNil(ruby, ln), stringOrNil(ruby, pre), stringOrNil(ruby, u), stringOrNil(ruby, val)
                    };
                }

                IRubyObject attr = RuntimeHelpers.invoke(context, attrClass, "new", args);
                rubyAttr.add(attr);
            }
        }

        if (localName == null || localName.isEmpty()) localName = getLocalPart(qName);
        populateCharacters();
        call("start_element_namespace",
             stringOrNil(ruby, localName),
             rubyAttr,
             stringOrNil(ruby, getPrefix(qName)),
             stringOrNil(ruby, uri),
             rubyNSAttr);
    }

    static final Set<String> EMPTY_ATTRS;
    static {
        final String[] emptyAttrs = {
            "checked", "compact", "declare", "defer", "disabled", "ismap", "multiple",
            "noresize", "nohref", "noshade", "nowrap", "readonly", "selected"
        };
        EMPTY_ATTRS = new HashSet<String>(Arrays.asList(emptyAttrs));
    }
    
    private static boolean isEmptyAttr(String name) {
        return EMPTY_ATTRS.contains(name);
    }
    
    public final Integer getLine() { // -1 if none is available
        final int line = locator.getLineNumber();
        return line == -1 ? null : line;
    }
    
    public final Integer getColumn() { // -1 if none is available
        final int column = locator.getColumnNumber();
        return column == -1 ? null : column - 1;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        populateCharacters();
        call("end_element_namespace",
             stringOrNil(ruby, localName),
             stringOrNil(ruby, getPrefix(qName)),
             stringOrNil(ruby, uri));
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        charactersBuilder.append(ch, start, length);
    }

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        populateCharacters();
        call("comment", ruby.newString(new String(ch, start, length)));
    }

    @Override
    public void startCDATA() {
        populateCharacters();
    }

    @Override
    public void endCDATA() {
        call("cdata_block", ruby.newString(charactersBuilder.toString()));
        charactersBuilder.setLength(0);
    }

    @Override
    public void error(SAXParseException saxpe) {
        try {
            call("error", ruby.newString(saxpe.getMessage()));
            addError(new RaiseException(XmlSyntaxError.createError(ruby, saxpe), true));
        } catch(RaiseException e) {
            addError(e);
        }
    }

    @Override
    public void fatalError(SAXParseException saxpe) {
        try {
            call("error", ruby.newString(saxpe.getMessage()));
            addError(new RaiseException(XmlSyntaxError.createFatalError(ruby, saxpe), true));
        } catch(RaiseException e) {
            addError(e);
        }
    }

    @Override
    public void warning(SAXParseException saxpe) {
        //System.out.println("warning: " + saxpe);
        call("warning", ruby.newString(saxpe.getMessage()));
    }

    protected synchronized void addError(RaiseException e) {
        errors.add(e);
    }

    public synchronized int getErrorCount() {
        return errors.size();
    }

    public synchronized RaiseException getLastError() {
        return errors.getLast();
    }

    private void call(String methodName) {
        ThreadContext context = ruby.getCurrentContext();
        RuntimeHelpers.invoke(context, document(context), methodName);
    }

    private void call(String methodName, IRubyObject argument) {
        ThreadContext context = ruby.getCurrentContext();
        RuntimeHelpers.invoke(context, document(context), methodName, argument);
    }

    private void call(String methodName, IRubyObject arg1, IRubyObject arg2) {
        ThreadContext context = ruby.getCurrentContext();
        RuntimeHelpers.invoke(context, document(context), methodName, arg1, arg2);
    }

    private void call(String methodName, IRubyObject arg1, IRubyObject arg2,
                      IRubyObject arg3) {
        ThreadContext context = ruby.getCurrentContext();
        RuntimeHelpers.invoke(context, document(context), methodName, arg1, arg2, arg3);
    }

    private void call(String methodName,
                      IRubyObject arg0,
                      IRubyObject arg1,
                      IRubyObject arg2,
                      IRubyObject arg3,
                      IRubyObject arg4) {
        ThreadContext context = ruby.getCurrentContext();
        RuntimeHelpers.invoke(context, document(context), methodName, arg0, arg1, arg2, arg3, arg4);
    }

    private IRubyObject document(ThreadContext context) {
        if (object instanceof RubyObject) {
            return ((RubyObject) object).fastGetInstanceVariable("@document");
        }
        return context.getRuntime().getNil();
    }

    protected void populateCharacters() {
        if (charactersBuilder.length() > 0) {
            call("characters", ruby.newString(charactersBuilder.toString()));
            charactersBuilder.setLength(0);
        }
    }
}
