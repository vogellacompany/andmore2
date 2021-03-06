/*******************************************************************************
 * Copyright (c) 2016 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.andmore.core.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.andmore.core.IConsoleService;
import org.eclipse.andmore.core.sdk.AndroidVirtualDevice;
import org.eclipse.andmore.core.sdk.IAndroidSDKService;

public class AndroidSDKService implements IAndroidSDKService {

	private String sdkLocation = System.getProperty("user.home") + "/Library/Android/sdk"; //$NON-NLS-1$ //$NON-NLS-2$

	private static class ErrorReaper extends Thread {
		private final BufferedReader err;
		private IConsoleService console;

		public ErrorReaper(InputStream err) {
			this.err = new BufferedReader(new InputStreamReader(err));
		}

		@Override
		public void run() {
			try {
				for (String line = err.readLine(); line != null; line = err.readLine()) {
					msg(line + '\n');
				}
			} catch (IOException e) {
				Activator.logError("reporting tools errors", e); //$NON-NLS-1$
			}
		}

		private void msg(String line) throws IOException {
			if (console == null) {
				console = Activator.getService(IConsoleService.class);
				console.activate();
			}
			console.writeError(line);
		}
	}

	@Override
	public Collection<AndroidVirtualDevice> getAVDs() throws IOException {
		Process proc = new ProcessBuilder(getAndroidCommand(), "list", "avd").start(); //$NON-NLS-1$ //$NON-NLS-2$
		ErrorReaper reaper = new ErrorReaper(proc.getErrorStream()); // $NON-NLS-1$
		reaper.start();

		Collection<AndroidVirtualDevice> avds = new ArrayList<>();
		Pattern field = Pattern.compile("\\s*([^\\s]+):\\s*(.*)"); //$NON-NLS-1$
		Pattern separator = Pattern.compile("--+"); //$NON-NLS-1$
		AndroidVirtualDevice avd = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				Matcher matcher = field.matcher(line);
				if (matcher.matches()) {
					String key = matcher.group(1);
					String value = matcher.group(2);
					if (avd == null) {
						avd = new AndroidVirtualDevice();
					}
					switch (key) {
					case "Name": //$NON-NLS-1$
						avd.setName(value);
						break;
					case "Device": //$NON-NLS-1$
						avd.setDevice(value);
						break;
					case "Path": //$NON-NLS-1$
						avd.setPath(value);
						break;
					case "Target": //$NON-NLS-1$
						avd.setTarget(value);
						break;
					case "Tag/ABI": //$NON-NLS-1$
						avd.setAbi(value);
						break;
					case "Skin": //$NON-NLS-1$
						avd.setSkin(value);
						break;
					}
					continue;
				}
				matcher = separator.matcher(line);
				if (matcher.matches()) {
					avds.add(avd);
					avd = null;
					continue;
				}
			}
		}
		if (avd != null) {
			avds.add(avd);
		}

		try {
			reaper.join();
		} catch (InterruptedException e) {
			Activator.log(e);
		}

		return avds;
	}

	@Override
	public void installAPK(Path apkPath) throws IOException {
		runCommand(getADBCommand(), "install", "-r", apkPath.toString()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public void startApp(String packageId, String activityId) throws IOException {
		runCommand(getADBCommand(), "shell", "am", "start", "-n", packageId + '/' + activityId); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static final Pattern propsPattern = Pattern.compile("\\[(.*)\\]:\\s*\\[(.*)\\]"); //$NON-NLS-1$

	@Override
	public Map<String, String> getProperties(String device) throws IOException {
		Map<String, String> props = new HashMap<>();
		Process proc = new ProcessBuilder(getADBCommand(), "-s", device, "shell", "getprop").redirectErrorStream(true) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.start();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				Matcher matcher = propsPattern.matcher(line);
				if (matcher.matches()) {
					props.put(matcher.group(1), matcher.group(2));
				} else {
					throw new IOException(String.format("Unexpected: %s", line)); //$NON-NLS-1$
				}
			}
		}
		return props;
	}

	@Override
	public String getProperty(String device, String key) throws IOException {
		Process proc = new ProcessBuilder(getADBCommand(), "-s", device, "shell", "getprop", key) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.redirectErrorStream(true).start();
		String value = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				value = line;
			}
		}
		try {
			int rc = proc.waitFor();
			if (rc != 0) {
				throw new IOException(String.format("Error %d: %s", rc, value)); //$NON-NLS-1$
			}
			return value;
		} catch (InterruptedException e) {
			return value;
		}
	}

	private static Pattern devicesPattern = Pattern.compile("(.*)\\s+(.*)"); //$NON-NLS-1$

	@Override
	public Collection<String> getDevices() throws IOException {
		List<String> devices = new ArrayList<>();
		Process proc = new ProcessBuilder(getADBCommand(), "devices").redirectErrorStream(true).start(); //$NON-NLS-1$
		try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				if (line.startsWith("List of devices attached")) { //$NON-NLS-1$
					continue;
				}
				Matcher matcher = devicesPattern.matcher(line);
				if (matcher.matches()) {
					devices.add(matcher.group(1));
				} else if (!line.trim().isEmpty()) {
					throw new IOException(String.format("Unxpected: %s", line));
				}
			}
		}

		return devices;
	}

	private void runCommand(String... cmd) throws IOException {
		Process proc = new ProcessBuilder(cmd).start(); // $NON-NLS-1$
		new ErrorReaper(proc.getErrorStream()).start(); // $NON-NLS-1$

		IConsoleService console = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				if (console == null) {
					console = Activator.getService(IConsoleService.class);
				}
				console.writeOutput(line);
				console.writeOutput("\n"); //$NON-NLS-1$
			}
		}
	}

	private String getAndroidCommand() {
		return sdkLocation + "/tools/android"; //$NON-NLS-1$
	}

	private String getADBCommand() {
		return sdkLocation + "/platform-tools/adb"; //$NON-NLS-1$
	}

}
