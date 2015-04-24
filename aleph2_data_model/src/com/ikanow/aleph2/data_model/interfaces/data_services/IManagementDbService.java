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
 ******************************************************************************/
package com.ikanow.aleph2.data_model.interfaces.data_services;

import java.util.Optional;

import org.checkerframework.checker.nullness.qual.NonNull;

import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketStatusBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;

/** The interface to the management database
 * @author acp
 */
public interface IManagementDbService {

	////////////////////////////////////
	
	// 1] Access
	
	// 1.1] JAR Library, includes:
	//      - Harvest Technologies
	//		- Harvest modules (no interface)
	//		- Enrichment modules
	//		- Analytics technologies
	//		- Analytics modules
	//		- Analytics utilities (no interface)
	//		- Access modules
	//		- Acccess utilities (no interface)
	
	/** Gets the store of shared JVM JAR libraries
	 * @return the CRUD service for the shared libraries store
	 */
	ICrudService<SharedLibraryBean> getSharedLibraryStore();
	
	//TODO: shared schemas
	
	//TODO: other "shares" - including "bucket artefacts" like "documents", "spreadsheets", "knowledge graphs" 
	
	//TODO: security and other things that we'll initially handle from IKANOW v1
	
	/** Gets or (lazily) creates a repository shared by all users of the specified library (eg Harvest Technology)
	 * @param clazz The class of the bean or object type desired (needed so the repo can reason about the type when deciding on optimizations etc)
	 * @param library a partial bean with the name or _id set
	 * @param sub_collection - arbitrary string, enables the user to split the per library state into multiple independent collections
	 * @return the CRUD service for the per library generic object store
	 */
	<T> ICrudService<T> getPerLibraryState(@NonNull Class<T> clazz, @NonNull SharedLibraryBean library, @NonNull Optional<String> sub_collection);
	
	////////////////////////////////////
	
	// 2] Importing
	
	// 2.1] Buckets	
	
	/** Gets the store of data buckets
	 * @return  the CRUD service for the bucket store
	 */
	ICrudService<DataBucketBean> getDataBucketStore();
	
	/** Gets the store of data bucket statuses
	 * @return  the CRUD service for the bucket status store
	 */
	ICrudService<DataBucketStatusBean> getDataBucketStatusStore();
	
	/** Gets or (lazily) creates a repository accessible from processing that occurs in the context of the specified bucket
	 * @param clazz The class of the bean or object type desired (needed so the repo can reason about the type when deciding on optimizations etc)
	 * @param bucket a partial bean with the name or _id set
	 * @param sub_collection - arbitrary string, enables the user to split the per library state into multiple independent collections
	 * @return the CRUD service for the per bucket generic object store
	 */
	<T> ICrudService<T> getPerBucketState(@NonNull Class<T> clazz, @NonNull DataBucketBean bucket, @NonNull Optional<String> sub_collection);
	
	////////////////////////////////////
	
	// 3] Analytics

	/** Gets the store of analytic threads
	 * @return the CRUD service for the analytic thread store
	 */
	ICrudService<AnalyticThreadBean> getAnalyticThreadStore();
	
	/** Gets or (lazily) creates a repository accessible from processing that occurs in the context of the specified analytic thread
	 * @param clazz The class of the bean or object type desired (needed so the repo can reason about the type when deciding on optimizations etc)
	 * @param analytic_thread a partial bean with the name or _id set
	 * @param sub_collection - arbitrary string, enables the user to split the per library state into multiple independent collections
	 * @return the CRUD service for the per analytic thread generic object store
	 */
	<T> ICrudService<T> getPerAnalyticThreadState(@NonNull Class<T> clazz, @NonNull AnalyticThreadBean analytic_thread, @NonNull Optional<String> sub_collection);
	
	////////////////////////////////////

	// X] Misc
	
	/** USE WITH CARE: this returns the driver to the underlying technology
	 *  shouldn't be used unless absolutely necessary!
	 * @param driver_class the class of the driver
	 * @param a string containing options in some technology-specific format
	 * @return a driver to the underlying technology. Will exception if you pick the wrong one!
	 */
	<T> T getUnderlyingPlatformDriver(Class<T> driver_class, Optional<String> driver_options);
}
