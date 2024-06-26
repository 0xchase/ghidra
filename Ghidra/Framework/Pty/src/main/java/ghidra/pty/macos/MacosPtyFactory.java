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
package ghidra.pty.macos;

import java.io.IOException;

import ghidra.pty.Pty;
import ghidra.pty.PtyFactory;
import ghidra.pty.unix.UnixPty;

public enum MacosPtyFactory implements PtyFactory {
	INSTANCE;

	@Override
	public Pty openpty(short cols, short rows) throws IOException {
		UnixPty pty = UnixPty.openpty(MacosIoctls.INSTANCE);
		if (cols != 0 && rows != 0) {
			pty.getChild().setWindowSize(cols, rows);
		}
		return pty;
	}

	@Override
	public String getDescription() {
		return "local (macOS)";
	}
}
