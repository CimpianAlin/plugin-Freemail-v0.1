/*
 * SingleAccountWatcher.java
 * This file is part of Freemail
 * Copyright (C) 2006,2007,2008 Dave Baker
 * Copyright (C) 2007,2008 Alexander Lehmann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.InterruptedException;
import java.util.HashMap;
import java.util.Map;

import freemail.fcp.ConnectionTerminatedException;
import freemail.utils.Logger;

public class SingleAccountWatcher implements Runnable {
	/**
	 * Whether the thread this service runs in should stop.
	 */
	protected volatile boolean stopping = false;

	public static final String CONTACTS_DIR = "contacts";
	public static final String INBOUND_DIR = "inbound";
	public static final String OUTBOUND_DIR = "outbound";
	private static final int MIN_POLL_DURATION = 60000; // in milliseconds

	//The timeouts for fetch and send. To avoid wasting time sleeping in the
	//run() loop, these should be >= MIN_POLL_DURATION. The main function of
	//these timeouts is to prevent sending/receiving large amounts of messages
	//from blocking other contacts and tasks.
	private static final long FETCH_TIMEOUT = 10 * MIN_POLL_DURATION;
	private static final long SEND_TIMEOUT = 2 * 10 * MIN_POLL_DURATION;

	private static final int MAILSITE_UPLOAD_INTERVAL = 60 * 60 * 1000;
	private final RTSFetcher rtsf;
	private long mailsite_last_upload;
	private final File obctdir;
	private final File ibctdir;
	private final FreemailAccount account;
	private final Map<File, OutboundContact> obContacts = new HashMap<File, OutboundContact>();

	SingleAccountWatcher(FreemailAccount acc) {
		this.account = acc;
		File contacts_dir = new File(account.getAccountDir(), CONTACTS_DIR);
		
		if (!contacts_dir.exists()) {
			contacts_dir.mkdir();
		}
		
		this.ibctdir = new File(contacts_dir, INBOUND_DIR);
		this.obctdir = new File(contacts_dir, OUTBOUND_DIR);
		this.mailsite_last_upload = 0;
		
		if (!this.ibctdir.exists()) {
			this.ibctdir.mkdir();
		}
		
		String rtskey=account.getProps().get("rtskey");

		if(rtskey==null) {
			Logger.error(this,"Your accprops file is missing the rtskey entry. This means it is broken, you will not be able to receive new contact requests.");
		}

		this.rtsf = new RTSFetcher("KSK@"+rtskey+"-", this.ibctdir, account);
		
		//this.mf = new MailFetcher(this.mb, inbound_dir, Freemail.getFCPConnection());
		
		// temporary info message until there's a nicer UI :)
		String freemailDomain=AccountManager.getFreemailDomain(account.getProps());
		if(freemailDomain!=null) {
			Logger.normal(this,"Secure Freemail address: <anything>@"+AccountManager.getFreemailDomain(account.getProps()));
		} else {
			Logger.error(this, "You do not have a freemail address USK. This account is really broken.");
		}
		
		String shortdomain = AccountManager.getKSKFreemailDomain(account.getProps());
		if (shortdomain != null) {
			Logger.normal(this,"Short Freemail address (*probably* secure): <anything>@"+shortdomain);

			String invalid=AccountManager.validateUsername(shortdomain);
			if(!invalid.equals("")) {
				Logger.normal(this,"Your short Freemail address contains invalid characters (\""+invalid+"\"), others may have problems sending you mail");
			}
		} else {
			Logger.normal(this,"You don't have a short Freemail address. You could get one by running Freemail with the --shortaddress option, followed by your account name and the name you'd like. For example, 'java -jar freemail.jar --shortaddress bob bob' will give you all addresses ending '@bob.freemail'. Try to pick something unique!");
		}
	}
	
	@Override
	public void run() {
		while (!stopping) {
			try {
				long start = System.currentTimeMillis();
				
				// is it time we inserted the mailsite?
				if (System.currentTimeMillis() > this.mailsite_last_upload + MAILSITE_UPLOAD_INTERVAL) {
					MailSite ms = new MailSite(account.getProps());
					if (ms.publish() > 0) {
						this.mailsite_last_upload = System.currentTimeMillis();
					}
				}
				if(stopping) {
					break;
				}
				// send any messages queued in contact outboxes
				Logger.debug(this, "sending any message in contact outboxes");
				File[] obcontacts = this.obctdir.listFiles(new outboundContactFilenameFilter());
				if (obcontacts != null) {
					int i;
					for (i = 0; i < obcontacts.length; i++) {
						try {
							OutboundContact obct = obContacts.get(obcontacts[i]);
							if(obct == null) {
								obct = new OutboundContact(account, obcontacts[i]);
								obContacts.put(obcontacts[i], obct);
							}
							obct.doComm(SEND_TIMEOUT);
						} catch (IOException ioe) {
							Logger.error(this, "Failed to create outbound contact - not sending mail");
						}
					}
				}
				Logger.debug(this, "polling rts");
				this.rtsf.poll();
				if(stopping) {
					break;
				}
				
				// poll for incoming message from all inbound contacts
				Logger.debug(this, "polling for incoming message from all inbound contacts");
				File[] ibcontacts = this.ibctdir.listFiles(new inboundContactFilenameFilter());
				if (ibcontacts != null) {
					int i;
					for (i = 0; i < ibcontacts.length; i++) {
						if (ibcontacts[i].getName().equals(RTSFetcher.LOGFILE)) continue;
						
						InboundContact ibct = new InboundContact(this.ibctdir, ibcontacts[i].getName());
						
						ibct.fetch(account.getMessageBank(), FETCH_TIMEOUT);
					}
				}
				if(stopping) {
					break;
				}
			
				long runtime = System.currentTimeMillis() - start;
				
				if (MIN_POLL_DURATION - runtime > 0) {
					Thread.sleep(MIN_POLL_DURATION - runtime);
				}
			} catch (ConnectionTerminatedException cte) {

			} catch (InterruptedException ie) {
				Logger.debug(this, "SingleAccountWatcher interrupted, stopping");
				kill();
				break;
			}
		}
	}

	/**
	 * Terminate the run method
	 */
	public void kill() {
		stopping = true;
	}

	private static class outboundContactFilenameFilter implements FilenameFilter {
		// check that each dir is a base32 encoded filename
		@Override
		public boolean accept(File dir, String name ) {
			return name.matches("[A-Za-z2-7]+");
		}
	}

	private static class inboundContactFilenameFilter implements FilenameFilter {
		// check that each dir is a freenet key
		@Override
		public boolean accept(File dir, String name ) {
			return name.matches("[A-Za-z0-9~-]+,[A-Za-z0-9~-]+,[A-Za-z0-9~-]+");
		}
	}

}
