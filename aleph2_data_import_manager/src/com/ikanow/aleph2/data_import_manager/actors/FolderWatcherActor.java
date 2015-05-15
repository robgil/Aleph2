/*******************************************************************************
* Copyright 2015, The IKANOW Open Source Project.
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License, version 3,
* as published by the Free Software Foundation.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
******************************************************************************/
package com.ikanow.aleph2.data_import_manager.actors;

import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;
import akka.actor.Cancellable;
import akka.actor.UntypedActor;

public class FolderWatcherActor extends UntypedActor {

	private final Cancellable tick = getContext()
			.system()
			.scheduler()
			.schedule(Duration.create(500, TimeUnit.MILLISECONDS),
					Duration.create(1000, TimeUnit.MILLISECONDS), getSelf(),
					"tick", getContext().dispatcher(), null);

	@Override
	public void postStop() {
		tick.cancel();
	}

	@Override
	public void onReceive(Object message) throws Exception {
		if (message.equals("tick")) {
			// do something useful here
		} else {
			unhandled(message);
		}
	}
}