/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

import android.net.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Locale;

/** Inline IP/proxy options. No credentials are accepted, stored or logged here. */
final class LambdaNetworkOptions {
    private boolean manualIp,manualProxy;
    private final EditText address,prefix,gateway,dns,proxyHost,proxyPort;
    private String t(String es,String en){return Locale.getDefault().getLanguage().equals("es")?es:en;}
    LambdaNetworkOptions(LambdaUi ui,LinearLayout parent){
        LinearLayout panel=ui.column();panel.setVisibility(View.GONE);
        Button expand=ui.link(t("Opciones avanzadas","Advanced options"),"wifi-cog",()->panel.setVisibility(panel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        parent.addView(expand);parent.addView(panel);
        LinearLayout ip=ui.column();
        ui.binary(panel,t("IP automática (DHCP)","Automatic IP (DHCP)"),t("IP estática","Static IP"),true,on->{manualIp=!on;ip.setVisibility(on?View.GONE:View.VISIBLE);});
        address=ui.field(ip,t("Dirección IPv4","IPv4 address"),InputType.TYPE_CLASS_TEXT);
        prefix=ui.field(ip,t("Longitud del prefijo (0–32)","Prefix length (0–32)"),InputType.TYPE_CLASS_NUMBER);prefix.setText("24");
        gateway=ui.field(ip,t("Puerta de enlace (opcional)","Gateway (optional)"),InputType.TYPE_CLASS_TEXT);
        dns=ui.field(ip,t("DNS, separados por comas","DNS addresses, comma-separated"),InputType.TYPE_CLASS_TEXT);
        panel.addView(ip);ip.setVisibility(View.GONE);
        LinearLayout proxy=ui.column();
        ui.binary(panel,t("Sin proxy","No proxy"),t("Proxy manual","Manual proxy"),true,on->{manualProxy=!on;proxy.setVisibility(on?View.GONE:View.VISIBLE);});
        proxyHost=ui.field(proxy,t("Servidor proxy","Proxy hostname"),InputType.TYPE_CLASS_TEXT);
        proxyPort=ui.field(proxy,t("Puerto","Port"),InputType.TYPE_CLASS_NUMBER);
        panel.addView(proxy);proxy.setVisibility(View.GONE);
    }
    IpConfiguration build(){
        IpConfiguration.Builder result=new IpConfiguration.Builder();
        if(manualIp){
            InetAddress ip;
            try{ip=InetAddresses.parseNumericAddress(address.getText().toString().trim());if(!(ip instanceof Inet4Address))throw new IllegalArgumentException();}
            catch(IllegalArgumentException e){throw new IllegalArgumentException(t("Introduce una dirección IPv4 válida.","Enter a valid IPv4 address."));}
            int bits;try{bits=Integer.parseInt(prefix.getText().toString().trim());if(bits<0||bits>32)throw new IllegalArgumentException();}
            catch(IllegalArgumentException e){throw new IllegalArgumentException(t("El prefijo debe estar entre 0 y 32.","Prefix length must be between 0 and 32."));}
            StaticIpConfiguration.Builder fixed=new StaticIpConfiguration.Builder().setIpAddress(new LinkAddress(ip,bits));
            String g=gateway.getText().toString().trim();
            try{if(!g.isEmpty()){InetAddress gw=InetAddresses.parseNumericAddress(g);if(!(gw instanceof Inet4Address))throw new IllegalArgumentException();fixed.setGateway(gw);}}
            catch(IllegalArgumentException e){throw new IllegalArgumentException(t("Revisa la puerta de enlace.","Check the gateway address."));}
            ArrayList<InetAddress> servers=new ArrayList<>();
            try{for(String item:dns.getText().toString().split(",")){if(!item.trim().isEmpty())servers.add(InetAddresses.parseNumericAddress(item.trim()));}}
            catch(IllegalArgumentException e){throw new IllegalArgumentException(t("Revisa las direcciones DNS.","Check the DNS addresses."));}
            if(servers.isEmpty())throw new IllegalArgumentException(t("Añade al menos un servidor DNS.","Add at least one DNS server."));
            result.setStaticIpConfiguration(fixed.setDnsServers(servers).build());
        }
        if(manualProxy){
            try{String host=proxyHost.getText().toString().trim();int port=Integer.parseInt(proxyPort.getText().toString().trim());
                if(host.isEmpty()||host.contains("://")||port<1||port>65535)throw new IllegalArgumentException();
                ProxyInfo proxy=ProxyInfo.buildDirectProxy(host,port);if(!proxy.isValid())throw new IllegalArgumentException();result.setHttpProxy(proxy);
            }catch(IllegalArgumentException e){throw new IllegalArgumentException(t("Revisa el servidor y puerto del proxy (1–65535).","Check the proxy hostname and port (1–65535)."));}
        }
        return result.build();
    }
}
