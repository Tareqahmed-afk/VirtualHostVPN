/*
 **Copyright (C) 2017  xfalcon
 **
 **This program is free software: you can redistribute it and/or modify
 **it under the terms of the GNU General Public License as published by
 **the Free Software Foundation, either version 3 of the License, or
 **(at your option) any later version.
 **
 **This program is distributed in the hope that it will be useful,
 **but WITHOUT ANY WARRANTY; without even the implied warranty of
 **MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **GNU General Public License for more details.
 **
 **You should have received a copy of the GNU General Public License
 **along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **
 */

package com.github.xfalcon.vhosts.vservice;

import android.util.Log;
import android.util.Patterns;

import com.github.xfalcon.vhosts.Aplikasi;
import com.github.xfalcon.vhosts.util.LogUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DnsChange {

    static String TAG = DnsChange.class.getSimpleName();
    static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS4 = null;
    static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS6 = null;


    public static ByteBuffer handle_dns_packet(Packet packet) {
        if (DOMAINS_IP_MAPS4 == null) {
            LogUtils.d(TAG, "DOMAINS_IP_MAPS IS　NULL　HOST FILE ERROR");
            return null;
        }
        try {
            ByteBuffer packet_buffer = packet.backingBuffer;
            packet_buffer.mark();
            byte[] tmp_bytes = new byte[packet_buffer.remaining()];
            packet_buffer.get(tmp_bytes);
            packet_buffer.reset();
            Message message = new Message(tmp_bytes);
            Record question = message.getQuestion();
            ConcurrentHashMap<String, String> DOMAINS_IP_MAPS;
            int type = question.getType();
            if (type == Type.A)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS4;
            else if (type == Type.AAAA)
                DOMAINS_IP_MAPS = DOMAINS_IP_MAPS6;
            else return null;
            Name query_domain = message.getQuestion().getName();
            String query_string = query_domain.toString();
            LogUtils.d(TAG, "query: " + question.getType() + " :" + query_string);
            if (!DOMAINS_IP_MAPS.containsKey(query_string)) {
                query_string = "." + query_string;
                int j = 0;
                while (true) {
                    int i = query_string.indexOf(".", j);
                    if (i == -1) {
                        return queryDNS(query_string,message,question,query_domain,packet_buffer,packet);
                    }
                    String str = query_string.substring(i);

                    if (".".equals(str) || "".equals(str)) {
                        return queryDNS(query_string,message,question,query_domain,packet_buffer,packet);
                    }
                    if (DOMAINS_IP_MAPS.containsKey(str)) {
                        query_string = str;
                        break;
                    }
                    j = i + 1;
                }
            }
            InetAddress address = Address.getByAddress(DOMAINS_IP_MAPS.get(query_string));
            Record record;
            if (type == Type.A) record = new ARecord(query_domain, 1, 86400, address);
            else record = new AAAARecord(query_domain, 1, 86400, address);
            message.addRecord(record, 1);
            message.getHeader().setFlag(Flags.QR);
            packet_buffer.limit(packet_buffer.capacity());
            packet_buffer.put(message.toWire());
            packet_buffer.limit(packet_buffer.position());
            packet_buffer.reset();
            packet.swapSourceAndDestination();
            packet.updateUDPBuffer(packet_buffer, packet_buffer.remaining());
            packet_buffer.position(packet_buffer.limit());
            LogUtils.d(TAG, "hit: " + question.getType() + " :" + query_domain.toString() + " :" + address.getHostName());
            return packet_buffer;
        } catch (Exception e) {
            LogUtils.d(TAG, "dns hook error", e);
            return null;
        }

    }

    public static ByteBuffer queryDNS(String host, Message message, Record question,Name query_domain,ByteBuffer packet_buffer,Packet packet){
        LogUtils.d(TAG, "queryCloudflareDNS: "+ host);
        String ipnya = "";
        try {
            if(host.startsWith(".")){
                host = host.substring(1);
            }
            if(host.endsWith(".")){
                host = host.substring(0,host.length()-1);
            }
            if(!Patterns.WEB_URL.matcher(host).matches()){
                LogUtils.d(TAG, host+" NOT VALID");
                return processDNS("0.0.0.0",message,question,query_domain,packet_buffer,packet);
            }
            File file = new File(Aplikasi.me.getCacheDir(),"dns");
            if(!file.exists()) file.mkdir();
            file = new File(file,host);
            if(file.exists() && System.currentTimeMillis()-file.lastModified()<21600000L){
                LogUtils.d(TAG, host+" from cache");
                StringBuilder text = new StringBuilder();
                try {
                    BufferedReader br = new BufferedReader(new FileReader(file));
                    String line;

                    while ((line = br.readLine()) != null) {
                        text.append(line);
                        text.append('\n');
                    }
                    br.close();
                    return processDNS(text.toString().trim(),message,question,query_domain,packet_buffer,packet);
                }
                catch (Exception e) {
                    LogUtils.d(TAG, "cache error : "+e.getMessage());
                }

            }else {
                LogUtils.d(TAG, "queryCloudflareDNS: " + host);

                JSONArray answer = getCloudflare("https://1.1.1.1/dns-query?name=" + host + "&type=A");
                Log.d(TAG, answer.toString());
                int jml = answer.length();
                if (jml > 0) {
                    for (int n = 0; n < jml; n++) {
                        JSONObject dnss = answer.getJSONObject(n);
                        if (dnss.getInt("type") == 1) {
                            ipnya = dnss.getString("data");
                            break;
                        }
                    }

                    try {
                        // save to cache
                        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
                        bw.write(ipnya);
                        bw.close();
                    }catch (IOException e) {
                        Log.e("Exception", "File write failed: " + e.toString());
                    }

                    return processDNS(ipnya,message,question,query_domain,packet_buffer,packet);
                }
            }
        }catch (Exception e){
            LogUtils.d(TAG, "queryCloudflareDNS Error: " + e.getMessage() + " : " + host+" | "+ipnya );
        }

        LogUtils.d(TAG, "queryCloudflareDNS: "+ host+" Not Found");
        return null;
    }

    public static ByteBuffer processDNS(String ipAdd, Message message, Record question,Name query_domain,ByteBuffer packet_buffer,Packet packet){
        try{
            int type = question.getType();
            InetAddress address = Address.getByAddress(ipAdd);
            Record record;
            if (type == Type.A) record = new ARecord(query_domain, 1, 86400, address);
            else record = new AAAARecord(query_domain, 1, 86400, address);
            message.addRecord(record, 1);
            message.getHeader().setFlag(Flags.QR);
            packet_buffer.limit(packet_buffer.capacity());
            packet_buffer.put(message.toWire());
            packet_buffer.limit(packet_buffer.position());
            packet_buffer.reset();
            packet.swapSourceAndDestination();
            packet.updateUDPBuffer(packet_buffer, packet_buffer.remaining());
            packet_buffer.position(packet_buffer.limit());
            LogUtils.d(TAG, "queryCloudflareDNS: " + question.getType() + " :" + query_domain.toString() + " : " + address.getHostName());
            return packet_buffer;
        }catch (Exception e){
            LogUtils.d(TAG, "queryCloudflareDNS Error: " + e.getMessage() + " :  | "+ipAdd );
        }
        return null;
    }


    private static JSONArray getCloudflare(String target){
        LogUtils.d(TAG, "getCloudflare: " + target);
        HttpURLConnection connection = null;
        BufferedReader bufferedReader = null;
        try{
            URL url = new URL(target);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/dns-json");
            connection.connect();
            InputStream inputStream = connection.getInputStream();
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuffer stringBuffer = new StringBuffer();

            String line ="";
            while((line= bufferedReader.readLine())!=null){
                stringBuffer.append(line);
            }

            LogUtils.d(TAG, "getCloudflare: " + stringBuffer.toString());
            return new JSONObject(stringBuffer.toString()).getJSONArray("Answer");
        }catch(IOException e) {
            e.printStackTrace();
        }catch (Exception e){

        }finally{
            connection.disconnect();

            try{
                bufferedReader.close();
            }catch (IOException e){
                e.printStackTrace();
            }
        }
        return null;
    }

    public static int handle_hosts(InputStream inputStream) {
        String STR_COMMENT = "#";
        String HOST_PATTERN_STR = "^\\s*(" + STR_COMMENT + "?)\\s*(\\S*)\\s*([^" + STR_COMMENT + "]*)" + STR_COMMENT + "?(.*)$";
        Pattern HOST_PATTERN = Pattern.compile(HOST_PATTERN_STR);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            DOMAINS_IP_MAPS4 = new ConcurrentHashMap<>();
            DOMAINS_IP_MAPS6 = new ConcurrentHashMap<>();
            while (!Thread.interrupted() && (line = reader.readLine()) != null) {
                if (line.length() > 1000 || line.startsWith(STR_COMMENT)) continue;
                Matcher matcher = HOST_PATTERN.matcher(line);
                if (matcher.find()) {
                    String ip = matcher.group(2).trim();
                    try {
                        Address.getByAddress(ip);
                    } catch (Exception e) {
                        continue;
                    }
                    if (ip.contains(":")) {
                        DOMAINS_IP_MAPS6.put(matcher.group(3).trim() + ".", ip);
                    } else {
                        DOMAINS_IP_MAPS4.put(matcher.group(3).trim() + ".", ip);
                    }
                }
            }
            reader.close();
            inputStream.close();
            LogUtils.d(TAG, DOMAINS_IP_MAPS4.toString());
            LogUtils.d(TAG, DOMAINS_IP_MAPS6.toString());
            return DOMAINS_IP_MAPS4.size() + DOMAINS_IP_MAPS6.size();
        } catch (IOException e) {
            LogUtils.d(TAG, "Hook dns error", e);
            return 0;
        }
    }

}
