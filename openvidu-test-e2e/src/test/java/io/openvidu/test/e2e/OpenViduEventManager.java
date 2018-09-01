/*
 * (C) Copyright 2017-2018 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.openvidu.test.e2e;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Manager event class for BrowserUser. Collects, cleans and stores events from
 * openvidu-testapp
 *
 * @author Pablo Fuente (pablofuenteperez@gmail.com)
 * @since 1.1.1
 */
public class OpenViduEventManager {

	private static class RunnableCallback implements Runnable {

		private final Consumer<JSONObject> callback;
		private JSONObject eventResult;

		public RunnableCallback(Consumer<JSONObject> callback) {
			this.callback = callback;
		}

		public void setEventResult(JSONObject json) {
			this.eventResult = json;
		}

		@Override
		public void run() {
			callback.accept(this.eventResult);
		}
	}

	private Thread pollingThread;
	private ExecutorService execService = Executors.newCachedThreadPool();
	private WebDriver driver;
	private Queue<JSONObject> eventQueue;
	private Map<String, Collection<RunnableCallback>> eventCallbacks;
	private Map<String, AtomicInteger> eventNumbers;
	private Map<String, CountDownLatch> eventCountdowns;
	private AtomicBoolean isInterrupted = new AtomicBoolean(false);
	private int timeOfWaitInSeconds;

	public OpenViduEventManager(WebDriver driver, int timeOfWaitInSeconds) {
		this.driver = driver;
		this.eventQueue = new ConcurrentLinkedQueue<JSONObject>();
		this.eventCallbacks = new ConcurrentHashMap<>();
		this.eventNumbers = new ConcurrentHashMap<>();
		this.eventCountdowns = new ConcurrentHashMap<>();
		this.timeOfWaitInSeconds = timeOfWaitInSeconds;
	}

	public void startPolling() {

		Thread.UncaughtExceptionHandler h = new Thread.UncaughtExceptionHandler() {
			public void uncaughtException(Thread th, Throwable ex) {
				if (ex.getClass().getSimpleName().equals("NoSuchSessionException")) {
					System.err.println("Disposing driver when running 'executeScript'");
				}
			}
		};

		this.pollingThread = new Thread(() -> {
			while (!this.isInterrupted.get()) {
				this.getEventsFromBrowser();
				this.emitEvents();
			}
		});
		this.pollingThread.setUncaughtExceptionHandler(h);
		this.pollingThread.start();
	}

	public void stopPolling() {
		this.eventCallbacks.clear();
		this.eventCountdowns.clear();
		this.eventNumbers.clear();
		this.isInterrupted.set(true);
		this.pollingThread.interrupt();
	}

	public void on(String eventName, Consumer<JSONObject> callback) {
		this.eventCallbacks.putIfAbsent(eventName, new HashSet<>());
		this.eventCallbacks.get(eventName).add(new RunnableCallback(callback));
	}

	public void off(String eventName) {
		this.eventCallbacks.remove(eventName);
	}

	// 'eventNumber' is accumulative for event 'eventName' for one page while it is
	// not refreshed
	public void waitUntilEventReaches(String eventName, int eventNumber) throws Exception {
		this.waitUntilEventReaches(eventName, eventNumber, this.timeOfWaitInSeconds, true);
	}

	public void waitUntilEventReaches(String eventName, int eventNumber, int secondsOfWait, boolean printTimeoutError)
			throws Exception {
		CountDownLatch eventSignal = new CountDownLatch(eventNumber);
		this.setCountDown(eventName, eventSignal);
		try {
			if (!eventSignal.await(secondsOfWait * 1000, TimeUnit.MILLISECONDS)) {
				throw (new TimeoutException());
			}
		} catch (InterruptedException | TimeoutException e) {
			if (printTimeoutError) {
				e.printStackTrace();
			}
			throw e;
		}
	}

	public boolean assertMediaTracks(Iterable<WebElement> videoElements, boolean audioTransmission,
			boolean videoTransmission) {
		boolean success = true;
		for (WebElement video : videoElements) {
			success = success && (audioTransmission == this.hasAudioTracks(video))
					&& (videoTransmission == this.hasVideoTracks(video));
			if (!success)
				break;
		}
		return success;
	}

	private AtomicInteger getNumEvents(String eventName) {
		return this.eventNumbers.computeIfAbsent(eventName, k -> new AtomicInteger(0));
	}

	private void setCountDown(String eventName, CountDownLatch cd) {
		this.eventCountdowns.put(eventName, cd);
		for (int i = 0; i < getNumEvents(eventName).get(); i++) {
			cd.countDown();
		}
	}

	private void emitEvents() {
		while (!this.eventQueue.isEmpty()) {
			JSONObject event = this.eventQueue.poll();

			System.out.println(event.get("event") + ": " + event);

			if (this.eventCallbacks.containsKey(event.get("event"))) {
				for (RunnableCallback callback : this.eventCallbacks.get(event.get("event"))) {
					callback.setEventResult(event);
					execService.submit(callback);
				}
			}
		}
	}

	private void getEventsFromBrowser() {
		String rawEvents = this.getAndClearEventsInBrowser();

		if (rawEvents == null || rawEvents.length() == 0) {
			return;
		}

		String[] events = rawEvents.replaceFirst("^<br>", "").split("<br>");
		JSONParser parser = new JSONParser();
		for (String e : events) {
			try {
				JSONObject event = (JSONObject) parser.parse(e);
				String eventName = (String) event.get("event");

				this.eventQueue.add(event);
				getNumEvents(eventName).incrementAndGet();

				if (this.eventCountdowns.get(eventName) != null) {
					this.eventCountdowns.get(eventName).countDown();
				}
			} catch (ParseException exc) {
				exc.printStackTrace();
			}
		}
	}

	private String getAndClearEventsInBrowser() {
		String events = (String) ((JavascriptExecutor) driver)
				.executeScript("var e = window.myEvents; window.myEvents = ''; return e;");
		return events;
	}

	public boolean hasMediaStream(WebElement videoElement) {
		boolean hasMediaStream = (boolean) ((JavascriptExecutor) driver).executeScript(
				"return (!!(document.getElementById('" + videoElement.getAttribute("id") + "').srcObject))");
		return hasMediaStream;
	}

	public Map<String, Long> getAverageRgbFromVideo(WebElement videoElement) {
		String script = "var callback = arguments[arguments.length - 1];" +
		        "var video = document.getElementById('" + videoElement.getAttribute("id") + "');" + 
				"var canvas = document.createElement('canvas');" + 
		        "canvas.height = video.videoHeight;" +
		        "canvas.width = video.videoWidth;" +
		        "var context = canvas.getContext('2d');" +
		        "context.drawImage(video, 0, 0, canvas.width, canvas.height);" +
		        "var imgEl = document.createElement('img');" +
		        "imgEl.src = canvas.toDataURL();" +
		        "var blockSize = 5;" +
		        "var defaultRGB = { r: 0, g: 0, b: 0 };" +
		        "context.drawImage(video, 0, 0, 220, 150);" +
		        "var dataURL = canvas.toDataURL();" +
		        "imgEl.onload = function () {" +
		          "let i = -4;" +
		          "var rgb = { r: 0, g: 0, b: 0 };" +
		          "let count = 0;" +
		          "if (!context) {" +
		          "  return defaultRGB;" +
		          "}" +

		          "var height = canvas.height = imgEl.naturalHeight || imgEl.offsetHeight || imgEl.height;" +
		          "var width = canvas.width = imgEl.naturalWidth || imgEl.offsetWidth || imgEl.width;" +
		          "let data;" +
		          "context.drawImage(imgEl, 0, 0);" +

		          "try {" +
		            "data = context.getImageData(0, 0, width, height);" +
		          "} catch (e) {" +
		            "return defaultRGB;" +
		          "}" +

		          "length = data.data.length;" +
		          "while ((i += blockSize * 4) < length) {" +
		            "++count;" +
		            "rgb.r += data.data[i];" +
		            "rgb.g += data.data[i + 1];" +
		            "rgb.b += data.data[i + 2];" +
		          "}" +

		          "rgb.r = ~~(rgb.r / count);" +
		          "rgb.g = ~~(rgb.g / count);" +
		          "rgb.b = ~~(rgb.b / count);" +

		          "console.warn(rgb);" +
		          "callback(rgb);" +
		        "};";
		Object averageRgb = ((JavascriptExecutor) driver).executeAsyncScript(script);
        return (Map<String, Long>)averageRgb;
	}

	public String getDimensionOfViewport() {
		String dimension = (String) ((JavascriptExecutor) driver)
				.executeScript("return (JSON.stringify({width: window.innerWidth, height: window.innerHeight}))");
		return dimension;
	}

	private boolean hasAudioTracks(WebElement videoElement) {
		boolean audioTracks = (boolean) ((JavascriptExecutor) driver)
				.executeScript("return ((document.getElementById('" + videoElement.getAttribute("id")
						+ "').srcObject.getAudioTracks().length > 0)" + "&& (document.getElementById('"
						+ videoElement.getAttribute("id") + "').srcObject.getAudioTracks()[0].enabled))");
		return audioTracks;
	}

	private boolean hasVideoTracks(WebElement videoElement) {
		boolean videoTracks = (boolean) ((JavascriptExecutor) driver)
				.executeScript("return ((document.getElementById('" + videoElement.getAttribute("id")
						+ "').srcObject.getVideoTracks().length > 0)" + "&& (document.getElementById('"
						+ videoElement.getAttribute("id") + "').srcObject.getVideoTracks()[0].enabled))");
		return videoTracks;
	}

}
