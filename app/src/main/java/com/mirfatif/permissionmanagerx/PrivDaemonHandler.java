package com.mirfatif.permissionmanagerx;

import android.os.SystemClock;
import android.util.Log;
import com.mirfatif.privtasks.Commands;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Socket;

public class PrivDaemonHandler {

  static final String TAG = "PrivDaemonHandler";
  static String DAEMON_PACKAGE_NAME = "com.mirfatif.privdaemon";
  static final String DAEMON_CLASS_NAME = "PrivDaemon";

  private PrintWriter cmdWriter;
  private ObjectInputStream responseInStream;

  private static PrivDaemonHandler mPrivDaemonHandler;

  public static synchronized PrivDaemonHandler getInstance() {
    if (mPrivDaemonHandler == null) {
      mPrivDaemonHandler = new PrivDaemonHandler();
    }
    return mPrivDaemonHandler;
  }

  private PrivDaemonHandler() {}

  public Boolean startDaemon() {
    MySettings mySettings = MySettings.getInstance();

    String dex = "daemon.dex";
    String script = "daemon.sh";
    File dexFile = new File(App.getContext().getExternalFilesDir(null), dex);
    File scriptFile = new File(App.getContext().getExternalFilesDir(null), script);

    int userId = Utils.getUserId();
    if (userId == 0) {
      if (Utils.extractionFails(dex, dexFile) || Utils.extractionFails(script, scriptFile)) {
        return false;
      }
    }

    int daemonUid = mySettings.getDaemonUid();
    File binDir = new File(App.getContext().getFilesDir(), "bin");

    File logFile = Utils.createCrashLogDir();

    String params =
        mySettings.DEBUG
            + " "
            + (logFile == null ? "null" : Utils.getOwnerFilePath(logFile))
            + " "
            + daemonUid
            + " "
            + userId
            + " "
            + Utils.getOwnerFilePath(dexFile)
            + " "
            + DAEMON_PACKAGE_NAME
            + " "
            + DAEMON_CLASS_NAME
            + " "
            + binDir
            + ":"
            + System.getenv("PATH");

    boolean isRootGranted = mySettings.isRootGranted();
    boolean useSocket = mySettings.useSocket();

    Adb adb = null;
    InputStream stdInStream = null;
    OutputStream stdOutStream = null;
    BufferedReader inReader;

    // required if running as root (ADBD or su)
    if (!Utils.extractBinary()) return false;

    if (isRootGranted) {
      if (useSocket) params += " " + Commands.CREATE_SOCKET;

      Process process = Utils.runCommand("su", TAG, false);
      if (process == null) return false;

      stdInStream = process.getInputStream();
      stdOutStream = process.getOutputStream();
      inReader = new BufferedReader(new InputStreamReader(stdInStream));
      cmdWriter = new PrintWriter(stdOutStream, true);

      Utils.runInBg(() -> readDaemonMessages(process, null));

    } else if (mySettings.isAdbConnected()) {
      params += " " + Commands.CREATE_SOCKET;
      useSocket = true;
      try {
        adb = new Adb("");
      } catch (IOException e) {
        e.printStackTrace();
        return false;
      }
      inReader = new BufferedReader(adb.getReader());
      cmdWriter = new PrintWriter(adb.getWriter(), true);

    } else {
      Log.e(TAG, "Cannot start privileged daemon without root or ADB shell");
      return false;
    }

    String command = "exec sh " + Utils.getOwnerFilePath(scriptFile);

    Log.i(TAG, "Sending command to shell: " + command);
    cmdWriter.println(command);

    // shell script reads from STDIN
    cmdWriter.println(params);

    int pid = 0;
    int port = 0;
    try {
      String line;
      while ((line = inReader.readLine()) != null) {
        if (line.startsWith(Commands.HELLO)) {
          pid = Integer.parseInt(line.split(":")[1]);
          port = Integer.parseInt(line.split(":")[2]);
          break;
        }
        Log.i(DAEMON_CLASS_NAME, line);
      }

      if (pid <= 0 || (useSocket && port <= 0)) {
        Log.e(TAG, "Bad or no response from privileged daemon");
        return false;
      }

      cmdWriter.println(Commands.GET_READY);

      // we have single input stream to read in case of ADB
      if (!isRootGranted) {
        Adb finalAdb = adb;
        Utils.runInBg(() -> readDaemonMessages(null, finalAdb));
      }

      if (!useSocket) {
        responseInStream = new ObjectInputStream(stdInStream);
      } else {
        // AdbLib redirects stdErr to stdIn. So create direct Socket.
        // Also in case of ADB binary, ADB over Network speed sucks
        Socket socket = new Socket(Inet4Address.getByAddress(new byte[] {127, 0, 0, 1}), port);
        socket.setTcpNoDelay(true);

        cmdWriter = new PrintWriter(socket.getOutputStream(), true);
        responseInStream = new ObjectInputStream(socket.getInputStream());

        if (isRootGranted) {
          stdOutStream.close();
          stdInStream.close();
        }
      }

      // get response to GET_READY command
      Object obj = responseInStream.readObject();
      if (!(obj instanceof String) || !obj.equals(Commands.GET_READY)) {
        Log.e(TAG, "Bad response from privileged daemon");
        return false;
      }
    } catch (IOException | ClassNotFoundException e) {
      e.printStackTrace();
      Log.e(TAG, "Error starting privileged daemon");
      return false;
    }

    mySettings.mPrivDaemonAlive = true;

    if (mySettings.doLogging == null) {
      mySettings.doLogging = false;
      command = "logcat --pid " + pid;

      if (isRootGranted) {
        command = binDir + "/set_priv -u " + daemonUid + " -g " + daemonUid + " -- " + command;
        if (Utils.doLoggingFails(new String[] {"su", "exec " + command})) return null;
      } else {
        Adb adbLogger;
        try {
          adbLogger = new Adb("exec " + command);
        } catch (IOException e) {
          e.printStackTrace();
          return null;
        }
        Utils.runInBg(() -> Utils.readLogcatStream(null, adbLogger));
      }
    }

    return true;
  }

  private void readDaemonMessages(Process process, Adb adb) {
    BufferedReader reader;
    if (process != null) {
      reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
    } else if (adb != null) {
      reader = new BufferedReader(adb.getReader());
    } else {
      return;
    }

    String line;
    try {
      while ((line = reader.readLine()) != null) {
        Log.e(DAEMON_CLASS_NAME, line);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      Utils.cleanProcess(reader, process, adb, "readDaemonMessages");

      if (MySettings.getInstance().mPrivDaemonAlive) {
        MySettings.getInstance().mPrivDaemonAlive = false;
        Log.e(TAG, "Privileged daemon died");
        SystemClock.sleep(5000);
        Log.i(TAG, "Restarting privileged daemon");
        startDaemon();
      }
    }
  }

  public Object sendRequest(String request) {
    synchronized (PrivDaemonHandler.class) {
      MySettings mySettings = MySettings.getInstance();
      if (!mySettings.mPrivDaemonAlive) {
        Log.e(TAG, request + ": Privileged daemon is dead");
        return null;
      }

      if (cmdWriter == null || responseInStream == null) {
        Log.e(TAG, "CommandWriter or ResponseReader is null");
        return null;
      }

      // to avoid getting restarted
      if (request.equals(Commands.SHUTDOWN)) mySettings.mPrivDaemonAlive = false;

      cmdWriter.println(request);

      if (request.equals(Commands.SHUTDOWN)) return null;

      try {
        return responseInStream.readObject();
      } catch (IOException | ClassNotFoundException e) {
        e.printStackTrace();

        Log.e(TAG, "Restarting privileged daemon");
        cmdWriter.println(Commands.SHUTDOWN);

        return null;
      }
    }
  }
}
