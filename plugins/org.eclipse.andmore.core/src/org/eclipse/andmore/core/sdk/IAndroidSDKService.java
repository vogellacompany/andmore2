/*******************************************************************************
 * Copyright (c) 2016 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.andmore.core.sdk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

public interface IAndroidSDKService {

	Collection<AndroidVirtualDevice> getAVDs() throws IOException;

	void installAPK(Path apkPath) throws IOException;

	void startApp(String packageId, String activityId) throws IOException;

	Map<String, String> getProperties(String device) throws IOException;

	String getProperty(String device, String key) throws IOException;

	Collection<String> getDevices() throws IOException;

}
