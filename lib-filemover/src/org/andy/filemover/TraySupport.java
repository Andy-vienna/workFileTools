package org.andy.filemover;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;

public class TraySupport {

	public static void initTray() {

		if (!SystemTray.isSupported()) {
			System.out.println("SystemTray wird nicht unterstützt.");
			return;
		}

		try {
			BufferedImage image = ImageIO.read(TraySupport.class.getResource("/move.png"));

			PopupMenu popup = new PopupMenu();
			MenuItem exitItem = new MenuItem("Beenden");
			exitItem.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent e) {
					System.exit(0);
				}
			});
			popup.add(exitItem);

			TrayIcon trayIcon = new TrayIcon(image, "FileMover aktiv", popup);
			trayIcon.setImageAutoSize(true);

			SystemTray.getSystemTray().add(trayIcon);
			trayIcon.displayMessage("FileMover", "FileMover gestartet", TrayIcon.MessageType.INFO);

		} catch (IOException | AWTException e) {
			e.printStackTrace();
		}

	}

}
