package org.las2mile.scrcpy;


import static android.org.apache.commons.codec.binary.Base64.encodeBase64String;
import android.content.Context;
import android.util.Log;
import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;


public class SendCommands {

    private Thread thread = null;
    private Context context;
    private volatile int status;


    public SendCommands() {

    }

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] arg0) {
                return encodeBase64String(arg0);
            }
        };
    }

    private AdbCrypto setupCrypto()
            throws NoSuchAlgorithmException, IOException {

        AdbCrypto c = null;
        try {
              c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            // Failed to read from file
            c = null;
        }


        if (c == null) {
            // We couldn't load a key, so let's generate a new one
            c = AdbCrypto.generateAdbKeyPair(getBase64Impl());
            // Save it
            c.saveAdbKeyPair(context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
            //Generated new keypair
        } else {
            //Loaded existing keypair
        }

        return c;
    }


    public int SendAdbCommands(Context context, final byte[] fileBase64, final String ip, String localip,
                               ScrcpySessionOptions options) {
        if (context == null || fileBase64 == null || fileBase64.length == 0
                || ip == null || ip.trim().isEmpty() || localip == null || localip.trim().isEmpty() || options == null) {
            return 2;
        }
        this.context = context;
        status = 1;
        final StringBuilder command = new StringBuilder();
        command.append(" CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server ");
        command.append(ScrcpySessionOptions.SERVER_VERSION);
        command.append(" log_level=info");
        command.append(" tcp_port=").append(ScrcpySessionOptions.SERVER_PORT);
        command.append(" tcp_client=").append(localip);
        command.append(" tunnel_forward=false");
        command.append(" video=true audio=").append(options.audioEnabled);
        command.append(" control=").append(!options.noControl);
        command.append(" video_codec=h264 audio_codec=aac");
        command.append(" max_size=").append(options.maxSize);
        command.append(" video_bit_rate=").append(options.videoBitRate);
        command.append(" max_fps=").append(options.maxFps);
        command.append(" power_on=").append(options.powerOn);
        command.append(" power_off_on_close=").append(options.powerOffOnClose);
        command.append(" stay_awake=").append(options.stayAwake);
        command.append(" show_touches=").append(options.showTouches);
        command.append(" cleanup=true send_dummy_byte=true send_device_meta=true");
        command.append(" send_frame_meta=true send_stream_meta=true;");

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    adbWrite(ip, fileBase64, command.toString());
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();
                    status = 2;
                }
            }
        });
        thread.start();
        int count = 0;
        while (status == 1 && count < 300) {
            Log.e("ADB", "Connecting...");
            try {
                Thread.sleep(100);
                count ++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (count == 300 && status == 1) {
            status = 2;
        }
        return status;
    }


    private void adbWrite(String ip, byte[] fileBase64, String command) throws IOException {

        AdbConnection adb = null;
        Socket sock = null;
        AdbCrypto crypto;
        AdbStream stream = null;

        try {
            crypto = setupCrypto();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            status = 2;
            return;
        } catch (IOException e) {
            e.printStackTrace();
            throw new IOException("Couldn't read/write keys");
        }

        try {
            sock = new Socket(ip, 5555);
            Log.e("scrcpy"," ADB socket connection successful");
        } catch (UnknownHostException e) {
            status = 2;
            throw new UnknownHostException(ip + " is no valid ip address");
        } catch (ConnectException e) {
            status = 2;
            throw new ConnectException("Device at " + ip + ":" + 5555 + " has no adb enabled or connection is refused");
        } catch (NoRouteToHostException e) {
            status = 2;
            throw new NoRouteToHostException("Couldn't find adb device at " + ip + ":" + 5555);
        } catch (IOException e) {
            e.printStackTrace();
            status = 2;
        }

        if (sock != null && status ==1) {
            try {
                adb = AdbConnection.create(sock, crypto);
                adb.connect();
            } catch (IllegalStateException e) {
                e.printStackTrace();
                status = 2;
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                status = 2;
                return;
            }
        }

        if (adb != null && status ==1) {

            try {
                stream = adb.open("shell:");
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                status = 2;
                return;
            }
        }

        if (stream != null && status ==1) {
            try {
                stream.write(" " + '\n');
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                status = 2;
                return;
            }
        }


        StringBuilder responses = new StringBuilder();
        boolean done = false;
        while (!done && stream != null && status == 1) {
            try {
                byte[] responseBytes = stream.read();
                responses.append(new String(responseBytes, StandardCharsets.US_ASCII));
                if (responses.length() > 4096) {
                    responses.delete(0, responses.length() - 4096);
                }
                done = responses.toString().endsWith("$ ") || responses.toString().endsWith("# ");
            } catch (InterruptedException | IOException e) {
                status = 2;
                e.printStackTrace();
            }
        }

        if (stream != null && status ==1) {
            int len = fileBase64.length;
            byte[] filePart = new byte[4056];
            int sourceOffset = 0;
            try {
                stream.write(" cd /data/local/tmp " + '\n');
                while (sourceOffset < len) {
                    if (len - sourceOffset >= 4056) {
                        System.arraycopy(fileBase64, sourceOffset, filePart, 0, 4056);  //Writing in 4KB pieces. 4096-40  ---> 40 Bytes for actual command text.
                        sourceOffset = sourceOffset + 4056;
                        String ServerBase64part = new String(filePart, StandardCharsets.US_ASCII);
                        stream.write(" echo " + ServerBase64part + " >> serverBase64" + '\n');
                        done = false;
                        while (!done) {
                            byte[] responseBytes = stream.read();
                            String response = new String(responseBytes, StandardCharsets.US_ASCII);
                            if (response.endsWith("$ ") || response.endsWith("# ")) {
                                done = true;
                            }
                        }
                    } else {
                        int rem = len - sourceOffset;
                        byte[] remPart = new byte[rem];
                        System.arraycopy(fileBase64, sourceOffset, remPart, 0, rem);
                        sourceOffset = sourceOffset + rem;
                        String ServerBase64part = new String(remPart, StandardCharsets.US_ASCII);
                        stream.write(" echo " + ServerBase64part + " >> serverBase64" + '\n');
                        done = false;
                        while (!done) {
                            byte[] responseBytes = stream.read();
                            String response = new String(responseBytes, StandardCharsets.US_ASCII);
                            if (response.endsWith("$ ") || response.endsWith("# ")) {
                                done = true;
                            }
                        }
                    }
                }
                stream.write(" base64 -d < serverBase64 > scrcpy-server.jar && rm serverBase64" + '\n');
                Thread.sleep(100);
                stream.write(command + '\n');
                if (status == 1) {
                    status = 0;
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                status =2;
                return;
            }
        }
        if (status == 1) {
            // The shell did not reach the point where the server command was sent.
            status = 2;
        }

    }

}
