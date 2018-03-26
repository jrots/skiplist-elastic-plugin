/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.jrots.skiplist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;
import java.util.Base64;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import java.io.InputStream;
import java.io.DataInputStream;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.index.fielddata.ScriptDocValues;

import org.roaringbitmap.RoaringBitmap;

class ByteBufferBackedInputStream extends InputStream {

    ByteBuffer buf;

    ByteBufferBackedInputStream(ByteBuffer buf) {
        this.buf = buf;
    }

    @Override
    public int available() throws IOException {
        return buf.remaining();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public int read() throws IOException {
        if (!buf.hasRemaining()) {
            return -1;
        }
        return 0xFF & buf.get();
    }

    @Override
    public int read(byte[] bytes) throws IOException {
        int len = Math.min(bytes.length, buf.remaining());
        buf.get(bytes, 0, len);
        return len;
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        len = Math.min(len, buf.remaining());
        buf.get(bytes, off, len);
        return len;
    }

    @Override
    public long skip(long n) {
        int len = Math.min((int) n, buf.remaining());
        buf.position(buf.position() + (int) n);
        return len;
    }
}

/**
 * An example script plugin that adds a {@link ScriptEngine} implementing expert scoring.
 */
public class SkipListPlugin extends Plugin implements ScriptPlugin {

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new SkipListPluginScriptEngine();
    }

    /** An example {@link ScriptEngine} that uses Lucene segment details to implement pure document frequency scoring. */
    // tag::expert_engine
    private static class SkipListPluginScriptEngine implements ScriptEngine {
        @Override
        public String getType() {
            return "skiplist";
        }

        @Override
        public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {
            if (context.equals(SearchScript.CONTEXT) == false) {
                throw new IllegalArgumentException(getType() + " scripts cannot be used for context [" + context.name + "]");
            }
            if ("roaring".equals(scriptSource)) {
                SearchScript.Factory factory = (p, lookup) -> new SearchScript.LeafFactory() {

                    final String field;
                    final String data;

                    String additionalField;
                    String additionalFieldComparator;

                    boolean additionalFieldCheck;
                    long additionalFieldValue;
                    double skipScore = -1.0d;
                    {
                        if (p.containsKey("field") == false) {
                            throw new IllegalArgumentException("Missing parameter [field]");
                        }
                        if (p.containsKey("data") == false) {
                            throw new IllegalArgumentException("Missing parameter [data]");
                        }

                        if (p.containsKey("additionalFieldToCheck")) {
                            if (p.containsKey("additionalFieldValue") == false) {
                                throw new IllegalArgumentException("Missing parameter [additionalFieldValue]");
                            }
                            try {
                                additionalFieldValue = Long.parseLong(p.get("additionalFieldValue").toString());
                            } catch (Exception e) {
                                throw new IllegalArgumentException("invalid parameter [additionalFieldValue] needs to be a long");
                            }

                            additionalFieldCheck = true;
                            additionalField = p.get("additionalFieldToCheck").toString();
                            if (p.containsKey("additionalFieldComparator")) {
                                additionalFieldComparator = p.get("additionalFieldComparator").toString();
                            } else {
                                additionalFieldComparator = "<";
                            }
                        }

                        field = p.get("field").toString();
                        data = p.get("data").toString();

                        try {
                            if (p.containsKey("score")) {
                                skipScore = Double.parseDouble(p.get("score").toString());
                            }
                        } catch (Exception e) {
                        }
                    }

                    @Override
                    public SearchScript newInstance(LeafReaderContext context) throws IOException {
                        final byte[] decoded = Base64.getDecoder().decode(data);
                        final ByteBuffer buf = ByteBuffer.wrap(decoded);
                        final ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(buf);
                        final RoaringBitmap bitmap_c = new RoaringBitmap();
                        try {
                            bitmap_c.deserialize(new DataInputStream(in));
                        } catch (Exception e) {
                            return new SearchScript(p, lookup, context) {
                                @Override
                                public double runAsDouble() {
                                    return getScore();
                                }
                            };
                        }
                        return new SearchScript(p, lookup, context) {
                            @Override
                            public double runAsDouble() {
                                try {
                                    final ScriptDocValues.Strings strValue = (ScriptDocValues.Strings) getLeafLookup().doc().get(field);
                                    final Integer documentId = Integer.parseInt(strValue.getValue(), 10);

                                    if (bitmap_c.contains(documentId)) {
                                        if (additionalFieldCheck) {
                                            if (!getLeafLookup().doc().containsKey(additionalField)) {
                                                return skipScore;
                                            }
                                            // only check values that exist.
                                            long storedValue = ((ScriptDocValues.Dates) getLeafLookup().doc().get(additionalField)).get(0).getMillis();

                                            if (additionalFieldComparator.equals("<")) {
                                                if (storedValue < additionalFieldValue) {
                                                    return skipScore;
                                                }
                                            } else if (additionalFieldComparator.equals(">")) {
                                                if (storedValue > additionalFieldValue) {
                                                    return skipScore;
                                                }
                                            }
                                        } else {
                                            return skipScore;
                                        }
                                    }
                                } catch(Exception e) {
                                   // System.out.println(e);
                                   return getScore();
                                }
                                return getScore();
                            }
                        };
                    }

                    @Override
                    public boolean needs_score() {
                        return false;
                    }
                };
                return context.factoryClazz.cast(factory);
            }
            throw new IllegalArgumentException("Unknown script name " + scriptSource);
        }

        @Override
        public void close() {
            // optionally close resources
        }
    }
}