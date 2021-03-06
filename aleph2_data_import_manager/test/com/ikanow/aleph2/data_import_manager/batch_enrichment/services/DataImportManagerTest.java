/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
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
 *******************************************************************************/
package com.ikanow.aleph2.data_import_manager.batch_enrichment.services;

import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.actors.BeBucketActor;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.actors.BucketEnrichmentMessage;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.module.DataImportManagerModule;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.services.mapreduce.IBeJobService;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.services.mapreduce.MockBeJobService;
import com.ikanow.aleph2.data_import_manager.batch_enrichment.utils.DataBucketTest;
import com.ikanow.aleph2.data_import_manager.stream_enrichment.services.IStormController;
import com.ikanow.aleph2.data_import_manager.stream_enrichment.services.LocalStormController;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.management_db.utils.ActorUtils;

public class DataImportManagerTest extends DataBucketTest{
    @SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger(DataImportManagerTest.class);

	protected DataImportManager dataImportManager = null;	
	protected IBeJobService beJoBService;

	@Before
	public void setupDependencies() throws Exception {
		super.setupDependencies();
		Injector serverInjector = ModuleUtils.createTestInjector(Arrays.asList(new DataImportManagerModule(){

			@Override
			protected void configureServices() {
			    bind(DataImportManager.class).in(Scopes.SINGLETON);
			    bind(IBeJobService.class).to(MockBeJobService.class).in(Scopes.SINGLETON);
			    bind(IStormController.class).to(LocalStormController.class).in(Scopes.SINGLETON);
			    
			}
			
		}), Optional.of(config));

		this.dataImportManager = serverInjector.getInstance(DataImportManager.class);
		this.beJoBService = serverInjector.getInstance(IBeJobService.class);		
	}	
	
	@Test
	@Ignore
	public void testCreate() throws Exception {
		assertNotNull(dataImportManager);
	}

	@Test
	@Ignore
	public void testStartStop() throws Exception {
		assertNotNull(dataImportManager);
		dataImportManager.start();	
		Thread.sleep(3000);
		dataImportManager.stop();		

	}

	@Test
	public void testFolderWatch() throws Exception {
		assertNotNull(dataImportManager);
		dataImportManager.folderWatch();	
		Thread.sleep(3000);
	}

	@Test
	public void testBeBucketActor() throws Exception {
		try {
			Props props = Props.create(BeBucketActor.class,_service_context.getStorageService(),beJoBService);
			ActorSystem system = _actor_context.getActorSystem();
		    ActorRef beBucketActor = system.actorOf(props,"beBucket1");		    
			createEnhancementBeanInDb();			
			beBucketActor.tell(new BucketEnrichmentMessage(bucketPath1, "/misc/bucket1", ActorUtils.BATCH_ENRICHMENT_ZOOKEEPER + buckeFullName1),  ActorRef.noSender());
			Thread.sleep(9000);
		}
		catch (Throwable t) {
			System.out.println(ErrorUtils.getLongForm("{0}", t));
			throw t;
		}
		
	}
	
}
