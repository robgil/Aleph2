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
package com.ikanow.aleph2.management_db.module;

import java.util.Arrays;
import java.util.Optional;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.management_db.services.CoreManagementDbService;

import fj.data.List;

/** Module enabling the management_db_service to be run standalone for testing purposes
 * @author acp
 */
public class StandaloneMangementDbModule extends AbstractModule {

	// (will have one of these 2 only)
	protected IManagementDbService _management_db_service;
	protected Injector _injector;
	
	/** Gets the injector generated by a call to the user constructor
	 * @return the guice injector
	 */
	@Nullable
	public Injector getInjector() {
		return _injector;
	}
	
	/** Default c'tor called by Guice
	 * @param management_db_service - the injected core management db service object
	 */
	@Inject
	protected StandaloneMangementDbModule(final @Named("management_db_service") IManagementDbService management_db_service) {
		_management_db_service = management_db_service;
		
		//DEBUG
		//System.out.println("Hello world from: " + this.getClass() + ": management_db_service=" + management_db_service);		
	}

	/** User constructor = to create a standalone app based on this service
	 * @param modules - first one is the underlying service (eg com.ikanow.aleph2.management_db.mongodb.services.[Mock]MongoDbManagementDbService), then a list of additional modules to load (eg com.ikanow.aleph2.management_db.mongodb.module.[Mock]MongoDbManagementDbModule)
	 */
	public StandaloneMangementDbModule(Optional<String[]> modules) {
		if (modules.isPresent()) {
			
			
			Iterable<Module> list = List.<String>iterableList(Arrays.asList(modules.get()))
													.drop(1)
													.map(m -> {
														try {
															return (Module)((Class.forName(m)).newInstance());
														}
														catch (Exception e) { 
															throw new RuntimeException("Cast to module: " + m, e);
														}
													})
													.cons(new CoreManagementDbModule(modules.get()[0]))
													.cons(new StandaloneMangementDbModule(Optional.empty()))
													;
			
			_injector = Guice.createInjector(list);
			
			_injector.getInstance(StandaloneMangementDbModule.class);
		}
	}

	/* (non-Javadoc)
	 * @see com.google.inject.AbstractModule#configure()
	 */
	@Override
	protected void configure() {
		this.bind(IManagementDbService.class).annotatedWith(Names.named("management_db_service")).to(CoreManagementDbService.class).in(Scopes.SINGLETON);
	}
	
}
