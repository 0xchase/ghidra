/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agent.gdb.manager.parsing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import org.apache.commons.collections4.MultiMapUtils;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import agent.gdb.manager.parsing.GdbParsingUtils.AbstractGdbParser;
import agent.gdb.manager.parsing.GdbParsingUtils.GdbParseError;

/**
 * A parser for GDB/MI records
 * 
 * <p>
 * While this is a much more machine-friendly format, it has some interesting idiosyncrasies that
 * make it annoying even within a machine. This class attempts to impose a nice abstraction of these
 * records while dealing with nuances particular to certain records, but in general. Examine GDB's
 * documentation for some example records.
 * 
 * <p>
 * There seem to be one primitive type and two (and a half?) aggregate types in these records. The
 * one primitive type is a string. The aggregates are lists and maps, and maybe "field lists" which
 * behave like multi-valued maps. Maps introduce IDs, which comprise the map keys or field names.
 * Strings are enclosed in double quotes, lists (including field lists) are enclosed in square
 * brackets, and maps (and sometimes field lists) are enclosed in curly braces. If the outermost
 * object is a field list, it may not be enclosed at all. These objects may be deeply nested. This
 * seems to resemble JSON, but rest assured, it is not. To avoid the need for backtracking or
 * recasting, maps are treated as field lists.
 */
public class GdbMiParser extends AbstractGdbParser {
	public static final String UNNAMED = "<unnamed>";

	/**
	 * The class used to store parsed maps and field lists
	 * 
	 * A field list is simply a multi-valued map. A single key may occur multiple times with
	 * different values.
	 */
	public static class GdbMiFieldList {
		/**
		 * For testing, start building a field list, as if parsed
		 * 
		 * @return a builder
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * The builder for constructing test field lists
		 */
		public static class Builder {
			private final GdbMiFieldList list;

			private Builder() {
				list = new GdbMiFieldList(false);
			}

			/**
			 * Add a key-value pair to the field list
			 * 
			 * @param key the key
			 * @param value the associated value
			 * @return this builder
			 */
			public Builder add(String key, Object value) {
				list.add(key, value);
				return this;
			}

			/**
			 * Build the field list
			 * 
			 * @return the field list
			 */
			public GdbMiFieldList build() {
				return list;
			}
		}

		/**
		 * A key-value entry in the field list
		 * 
		 * @param key the key
		 * @param value the value
		 */
		public record Entry(String key, Object value) {
		}

		private MultiValuedMap<String, Object> map = new ArrayListValuedHashMap<String, Object>();
		private MultiValuedMap<String, Object> unmodifiableMap =
			MultiMapUtils.unmodifiableMultiValuedMap(map);
		private final List<Entry> entryList = new ArrayList<>();
		private final List<Entry> unmodifiableEntries = Collections.unmodifiableList(entryList);
		private final boolean enclosed;

		private GdbMiFieldList(boolean enclosed) {
			this.enclosed = enclosed;
		}

		private void add(String key, Object value) {
			entryList.add(new Entry(key, value));
			map.put(key, value);
		}

		/**
		 * Get the list of entries, in order of appearance
		 * 
		 * @return the list of key-value entries
		 */
		public List<Entry> entries() {
			return unmodifiableEntries;
		}

		/**
		 * Get all values associated with the given key
		 * 
		 * @param key the key
		 * @return the unordered collection of values
		 */
		public Collection<Object> get(String key) {
			return unmodifiableMap.get(key);
		}

		/**
		 * Assume only a single value is associated with the key, and get that value
		 * 
		 * @param key the key
		 * @return the value
		 * @throws IllegalStateException if more than one value is associated
		 */
		public Object getSingleton(String key) {
			Collection<Object> col = map.get(key);
			if (col.size() == 0) {
				return null;
			}
			if (col.size() != 1) {
				throw new IllegalStateException("Key " + key + " is multi-valued: " + col);
			}
			return col.iterator().next();
		}

		/**
		 * Assume only a single string is associated with the key, and get that string
		 * 
		 * @param key the key
		 * @return the value
		 * @throws IllegalStateException if more than one value is associated
		 */
		public String getString(String key) {
			return (String) getSingleton(key);
		}

		/**
		 * Assume only a single list is associated with the key, and get that list
		 * 
		 * <p>
		 * For convenience, the list is cast to a list of elements of a given type. This cast is
		 * unchecked.
		 * 
		 * @param cls the type of elements in the list
		 * @param key the key
		 * @return the value
		 * @throws IllegalStateException if more than one value is associated
		 */
		@SuppressWarnings("unchecked")
		public <T> List<T> getListOf(Class<T> cls, String key) {
			return (List<T>) getSingleton(key);
		}

		/**
		 * Assume only a single field list is associated with the key, and get that list
		 * 
		 * @param key the key
		 * @return the value
		 * @throws IllegalStateException if more than one value is associated
		 */
		public GdbMiFieldList getFieldList(String key) {
			Object obj = getSingleton(key);
			if (obj instanceof List<?> list && list.isEmpty()) {
				return GdbMiFieldList.builder().build();
			}
			return (GdbMiFieldList) obj;
		}

		/**
		 * Check if a key is present in the field list
		 * 
		 * @param key the key
		 * @return true if present, false otherwise
		 */
		public boolean containsKey(String key) {
			return map.containsKey(key);
		}

		/**
		 * Count the number of entries (not keys) in the field list
		 * 
		 * @return the count
		 */
		public int size() {
			return entryList.size();
		}

		@Override
		public String toString() {
			return map.toString();
		}

		@Override
		public int hashCode() {
			return map.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof GdbMiFieldList)) {
				return false;
			}
			GdbMiFieldList that = (GdbMiFieldList) obj;
			return Objects.equals(this.map, that.map);
		}
	}

	protected static final Pattern COMMA = Pattern.compile(",");
	protected static final Pattern LBRACKET = Pattern.compile("\\[");
	protected static final Pattern RBRACKET = Pattern.compile("\\]");
	protected static final Pattern FIELD_ID = Pattern.compile("([0-9A-Za-z_]|-)+");
	protected static final Pattern EQUALS = Pattern.compile("=");
	protected static final Pattern LBRACE = Pattern.compile("\\{");
	protected static final Pattern RBRACE = Pattern.compile("\\}");

	/**
	 * Construct a parser of the given text
	 * 
	 * The static methods {@link #parseObject(CharSequence)}, {@link #parseString(CharSequence)},
	 * {@link #parseFields(CharSequence)} should probably be used instead.
	 * 
	 * @param text the text to parse
	 */
	public GdbMiParser(CharSequence text) {
		super(text);
	}

	/**
	 * Parse the object in the text
	 * 
	 * @param text the text to parse
	 * @return the object defined in the text
	 * @throws GdbParseError if no text matches the pattern
	 */
	public static Object parseObject(CharSequence text) throws GdbParseError {
		GdbMiParser parser = new GdbMiParser(text);
		Object result = parser.parseObject();
		parser.checkEmpty(true);
		return result;
	}

	/**
	 * Parse the string literal in the text
	 * 
	 * @param text the text to parse
	 * @return the string parsed
	 * @throws GdbParseError if no text matches the pattern
	 */
	public static String parseString(CharSequence text) throws GdbParseError {
		GdbMiParser parser = new GdbMiParser(text);
		String result = parser.parseString();
		parser.checkEmpty(true);
		return result;
	}

	/**
	 * Parse the fields in the text
	 * 
	 * @param text the text to parse
	 * @return the string parsed
	 * @throws GdbParseError if no text matches the pattern
	 */
	public static GdbMiFieldList parseFields(CharSequence text) throws GdbParseError {
		GdbMiParser parser = new GdbMiParser(text);
		GdbMiFieldList result = parser.parseFields(false);
		parser.checkEmpty(true);
		return result;
	}

	/**
	 * Parse the object at the cursor
	 * 
	 * @see #parseObject(CharSequence)
	 * @return the object
	 * @throws GdbParseError if no text matches
	 */
	public Object parseObject() throws GdbParseError {
		switch (peek(true)) {
			case '"':
				return parseString();
			case '[':
				return parseList();
			case '{':
				return parseMap();
			default:
				// TODO: I'm a little uneasy about this
				// It's basically a malformed map
				return parseFields(false);
		}
	}

	/**
	 * Parse the string at the cursor, undoing GDB's printchar transformation.
	 * 
	 * @see #parseString(CharSequence)
	 * @return the string
	 * @throws GdbParseError if no text matches the pattern
	 */
	public String parseString() throws GdbParseError {
		if ('"' != peek(false)) { // Keep whitespace that is in the string
			throw new GdbParseError("\"", buf);
		}
		buf.get(); // consume "

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		while (true) {
			char ch = buf.get();
			if (ch > 0xff) {
				throw new GdbParseError("byte", "U+" + String.format("%04X", ch));
			}
			else if (ch == '"') {
				break;
			}
			else if (ch != '\\') {
				baos.write(ch);
				continue;
			}

			/* Handle backslash-escape */
			ch = buf.get();
			switch (ch) {
				case 'n':
					baos.write('\n');
					break;
				case 'b':
					baos.write('\b');
					break;
				case 't':
					baos.write('\t');
					break;
				case 'f':
					baos.write('\f');
					break;
				case 'r':
					baos.write('\r');
					break;
				case 'e':
					baos.write(0x1b);
					break;
				case 'a':
					baos.write(0x07);
					break;
				case '0':
				case '1':
				case '2':
				case '3':
					char ch2 = buf.get();
					if (ch2 < '0' || ch2 > '9') {
						throw new GdbParseError("octal", "" + ch2);
					}
					char ch3 = buf.get();
					if (ch3 < '0' || ch3 > '9') {
						throw new GdbParseError("octal", "" + ch3);
					}
					int octchar = ((ch - '0') << 6) | ((ch2 - '0') << 3) | (ch3 - '0');
					baos.write(octchar);
					break;
				case '\\':
				case '"':
					baos.write(ch);
					break;
				default:
					throw new GdbParseError("escape", "" + ch);
			}
		}
		return baos.toString(StandardCharsets.UTF_8);
	}

	/**
	 * Parse the list at the cursor
	 * 
	 * @return the list
	 * @throws GdbParseError if no text matches the pattern
	 */
	public Object parseList() throws GdbParseError {
		match(LBRACKET, true);
		List<Object> result = new ArrayList<>();
		while (buf.hasRemaining()) {
			char c = peek(true);
			if (c == ']') {
				match(RBRACKET, false);
				break;
			}
			if (c == ',') {
				match(COMMA, false);
			}
			result.add(parseObject());
		}
		if (result.size() == 1) {
			Object maybeFieldList = result.get(0);
			if (maybeFieldList instanceof GdbMiFieldList) {
				GdbMiFieldList fieldList = (GdbMiFieldList) maybeFieldList;
				if (!fieldList.enclosed) {
					return maybeFieldList;
				}
			}
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * Parse the map at the cursor
	 * 
	 * @return the map (as a field list)
	 * @throws GdbParseError if no text matches the pattern
	 */
	public GdbMiFieldList parseMap() throws GdbParseError {
		match(LBRACE, true);
		GdbMiFieldList result = parseFields(true);
		match(RBRACE, true);
		return result;
	}

	/**
	 * Parse the fields at the cursor
	 * 
	 * @see #parseFields(CharSequence)
	 * @param enclosed true if the field list is enclosed in brackets/braces
	 * @return the field list
	 * @throws GdbParseError if no text matches the pattern
	 */
	public GdbMiFieldList parseFields(boolean enclosed) throws GdbParseError {
		GdbMiFieldList result = new GdbMiFieldList(enclosed);
		while (buf.hasRemaining()) {
			char c = peek(true);
			if (c == ']' || c == '}') {
				break;
			}
			if (c == ',') {
				match(COMMA, false);
			}
			c = peek(true);
			if (c == '{') {
				Object fieldVal = parseObject();
				result.add(UNNAMED, fieldVal);
				continue;
			}
			if (c == '"') {
				String bareString = parseString();
				result.add(null, bareString);
				continue;
			}
			String fieldId = match(FIELD_ID, true);
			match(EQUALS, true);
			Object fieldVal = parseObject();
			result.add(fieldId, fieldVal);
		}
		return result;
	}
}
