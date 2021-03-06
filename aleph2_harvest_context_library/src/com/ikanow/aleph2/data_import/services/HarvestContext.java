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
package com.ikanow.aleph2.data_import.services;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.ikanow.aleph2.core.shared.utils.LiveInjector;
import com.ikanow.aleph2.data_import.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketStatusBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.CrudUtils;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils.BeanTemplate;
import com.ikanow.aleph2.data_model.utils.CrudUtils.MultiQueryComponent;
import com.ikanow.aleph2.data_model.utils.CrudUtils.SingleQueryComponent;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.Patterns;
import com.ikanow.aleph2.data_model.utils.PropertiesUtils;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.distributed_services.data_model.DistributedServicesPropertyBean;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.ikanow.aleph2.distributed_services.utils.KafkaUtils;
import com.sun.xml.internal.rngom.binary.Pattern;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;

import fj.data.Either;

//TODO: ALEPH-12 wire up module config via signature

@SuppressWarnings("unused")
public class HarvestContext implements IHarvestContext {
	protected static final Logger _logger = LogManager.getLogger();	

	public static final String __MY_BUCKET_ID = "030e2b82-0285-11e5-a322-1697f925ec7b";
	public static final String __MY_TECH_LIBRARY_ID = "030e2b82-0285-11e5-a322-1697f925ec7c";
	public static final String __MY_MODULE_LIBRARY_ID = "030e2b82-0285-11e5-a322-1697f925ec7d";
	
	public enum State { IN_TECHNOLOGY, IN_MODULE };
	protected final State _state_name;
	
	protected static class MutableState {
		//TODO (ALEPH-19) logging information - will be genuinely mutable
		SetOnce<DataBucketBean> bucket = new SetOnce<>();
		SetOnce<SharedLibraryBean> technology_config = new SetOnce<>();
		SetOnce<SharedLibraryBean> module_config = new SetOnce<>();
		final SetOnce<ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> service_manifest_override = new SetOnce<>();
	};
	protected final MutableState _mutable_state = new MutableState(); 
	
	// (stick this injection in and then call injectMembers in IN_MODULE case)
	@Inject protected IServiceContext _service_context;	
	protected IManagementDbService _core_management_db;
	protected ICoreDistributedServices _distributed_services; 	
	protected IStorageService _storage_service;
	protected GlobalPropertiesBean _globals;
	
	protected Optional<IDataWriteService<String>> _crud_storage_service;
	protected Optional<IDataWriteService.IBatchSubservice<String>> _batch_storage_service;	
	
	protected final ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	private static ConcurrentHashMap<String, HarvestContext> static_instances = new ConcurrentHashMap<>();
	
	/**Guice injector
	 * @param service_context
	 */
	@Inject 
	public HarvestContext(final IServiceContext service_context) {
		_state_name = State.IN_TECHNOLOGY;
		_service_context = service_context;
		_core_management_db = service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
		_distributed_services = service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();
		_storage_service = service_context.getStorageService();
		_globals = service_context.getGlobalProperties();
		
		_batch_storage_service = Optional.empty(); //(can't call from IN_TECHNOLOGY)
		_crud_storage_service = Optional.empty();		
	}

	/** In-module constructor
	 */
	public HarvestContext() {
		_state_name = State.IN_MODULE;
		
		// Can't do anything until initializeNewContext is called
	}	
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the bucket for this harvest context instance
	 * @param this_bucket - the bucket to associated
	 * @returns whether the bucket has been updated (ie fails if it's already been set)
	 */
	public boolean setBucket(DataBucketBean this_bucket) {
		return _mutable_state.bucket.set(this_bucket);
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the library bean for this harvest context instance
	 * @param this_bucket - the library bean to be associated
	 * @returns whether the library bean has been updated (ie fails if it's already been set)
	 */
	public boolean setTechnologyConfig(SharedLibraryBean lib_config) {
		return _mutable_state.technology_config.set(lib_config);
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the optional module library bean for this context instance
	 * @param this_bucket - the library bean to be associated
	 * @returns whether the library bean has been updated (ie fails if it's already been set)
	 */
	public boolean setModuleConfig(final SharedLibraryBean lib_config) {
		return _mutable_state.module_config.set(lib_config);
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#initializeNewContext(java.lang.String)
	 */
	@Override
	public void initializeNewContext(final String signature) {
		try {
			// Inject dependencies
			
			final Config parsed_config = ConfigFactory.parseString(signature);
			final HarvestContext to_clone = static_instances.get(signature);			
			if (null != to_clone) { //copy the fields				
				_service_context = to_clone._service_context;
				_core_management_db = to_clone._core_management_db;
				_distributed_services = to_clone._distributed_services;	
				_storage_service = to_clone._storage_service;
				_globals = to_clone._globals;
				// (apart from bucket, which is handled below, rest of mutable state is not needed)
			}
			else {							
				ModuleUtils.initializeApplication(Collections.emptyList(), Optional.of(parsed_config), Either.right(this));
				_core_management_db = _service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
				_distributed_services = _service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();
				_storage_service = _service_context.getStorageService();
				_globals = _service_context.getGlobalProperties();
			}			
			// Get bucket 
			
			final BeanTemplate<DataBucketBean> retrieve_bucket = BeanTemplateUtils.from(parsed_config.getString(__MY_BUCKET_ID), DataBucketBean.class);
			_mutable_state.bucket.set(retrieve_bucket.get());
			final BeanTemplate<SharedLibraryBean> retrieve_library = BeanTemplateUtils.from(parsed_config.getString(__MY_TECH_LIBRARY_ID), SharedLibraryBean.class);
			_mutable_state.technology_config.set(retrieve_library.get());
			if (parsed_config.hasPath(__MY_MODULE_LIBRARY_ID)) {
				final BeanTemplate<SharedLibraryBean> retrieve_module = BeanTemplateUtils.from(parsed_config.getString(__MY_MODULE_LIBRARY_ID), SharedLibraryBean.class);
				_mutable_state.module_config.set(retrieve_module.get());				
			}
			
			_batch_storage_service = 
					(_crud_storage_service = _storage_service.getDataService()
												.flatMap(s -> 
															s.getWritableDataService(String.class, retrieve_bucket.get(), 
																Optional.of(IStorageService.StorageStage.json.toString()), Optional.empty()))
					)
					.flatMap(IDataWriteService::getBatchWriteSubservice)
					;			
			
			static_instances.put(signature, this);
		}
		catch (Exception e) {
			//DEBUG
			//System.out.println(ErrorUtils.getLongForm("{0}", e));			

			throw new RuntimeException(e);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getService(java.lang.Class, java.util.Optional)
	 */
	@Override
	public IServiceContext getServiceContext() {
		return _service_context;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#sendObjectToStreamingPipeline(java.util.Optional, com.fasterxml.jackson.databind.JsonNode)
	 */
	@Override
	public void sendObjectToStreamingPipeline(
			Optional<DataBucketBean> bucket, Either<JsonNode, Map<String, Object>> object) {
				
		final String obj_str =  object.either(JsonNode::toString, map -> _mapper.convertValue(map, JsonNode.class).toString());
		
		if (_batch_storage_service.isPresent()) {
			_batch_storage_service.get().storeObject(obj_str);
		}
		else if (_crud_storage_service.isPresent()){ // (super slow)
			_crud_storage_service.get().storeObject(obj_str);
		}				
		_distributed_services.produce(_distributed_services.generateTopicName(bucket.orElseGet(() -> _mutable_state.bucket.get()).full_name(), Optional.empty()), obj_str);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getHarvestContextLibraries(java.util.Optional)
	 */
	@Override
	public List<String> getHarvestContextLibraries(final Optional<Set<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> services) {
		
		// Consists of:
		// 1) This library 
		// 2) Libraries that are always needed:		
		//    - core distributed services (implicit)
		//    - management db (core + underlying + any underlying drivers)
		//    - data model 
		// 3) Any libraries associated with the services		
		
		if (_state_name == State.IN_TECHNOLOGY) {
			// This very JAR			
			final String this_jar = Lambdas.get(() -> {
				return LiveInjector.findPathJar(this.getClass(), "");	
			});
			
			// Data model			
			final String data_model_jar = Lambdas.get(() -> {
				return LiveInjector.findPathJar(_service_context.getClass(), "");	
			});
			
			// Libraries associated with services:
			final Set<String> user_service_class_files = services.map(set -> {
				return set.stream()
						.map(clazz_name -> _service_context.getService(clazz_name._1(), clazz_name._2()))
						.filter(service -> service.isPresent())
						.flatMap(service -> service.get().getUnderlyingArtefacts().stream())
						.map(artefact -> LiveInjector.findPathJar(artefact.getClass(), ""))
						.collect(Collectors.toSet());
			})
			.orElse(Collections.emptySet());
			
			// Mandatory services
			final Set<String> mandatory_service_class_files =
						Arrays.asList(
								_distributed_services.getUnderlyingArtefacts(),
								_service_context.getStorageService().getUnderlyingArtefacts(),
								_service_context.getCoreManagementDbService().getUnderlyingArtefacts() 
								)
							.stream()
							.flatMap(x -> x.stream())
							.map(service -> LiveInjector.findPathJar(service.getClass(), ""))
							.collect(Collectors.toSet());
			
			// Combine them together
			final List<String> ret_val = ImmutableSet.<String>builder()
							.add(this_jar)
							.add(data_model_jar)
							.addAll(user_service_class_files)
							.addAll(mandatory_service_class_files)
							.build()
							.stream()
							.filter(f -> (null != f) && !f.equals(""))
							.collect(Collectors.toList())
							;
			
			if (ret_val.isEmpty()) {
				_logger.warn("WARNING: no library files found, probably because this is running from an IDE - instead taking all JARs from: " + (_globals.local_root_dir() + "/lib/"));
			}
			
			return !ret_val.isEmpty()
					? ret_val
					:
					// Special case: no aleph2 libs found, this is almost certainly because this is being run from eclipse...
					Lambdas.get(() -> {
						try {
							return FileUtils.listFiles(new File(_globals.local_root_dir() + "/lib/"), new String[] { "jar" }, false)
										.stream()
										.map(File::toString)
										.collect(Collectors.toList());
						}
						catch (Exception e) {
							throw new RuntimeException("In eclipse/IDE mode, directory not found: " + (_globals.local_root_dir() + "/lib/"));
						}
					});
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getHarvestContextSignature(java.util.Optional)
	 */
	@Override
	public String getHarvestContextSignature(final Optional<DataBucketBean> bucket, 
			final Optional<Set<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> services)
	{		
		if (_state_name == State.IN_TECHNOLOGY) {
			// Returns a config object containing:
			// - set up for any of the services described
			// - all the rest of the configuration
			// - the bucket bean ID
			
			final Config full_config = ModuleUtils.getStaticConfig()
										.withoutPath(DistributedServicesPropertyBean.APPLICATION_NAME)
										.withoutPath("MongoDbManagementDbService.v1_enabled") // (special workaround for V1 sync service)
										;
	
			final Optional<Config> service_config = PropertiesUtils.getSubConfig(full_config, "service");
			
			final ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>> complete_services_set = 
					ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
							.addAll(services.orElse(Collections.emptySet()))
							.add(Tuples._2T(ICoreDistributedServices.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, Optional.empty()))
							.add(Tuples._2T(ISecurityService.class, Optional.empty()))
							.add(Tuples._2T(IStorageService.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, IManagementDbService.CORE_MANAGEMENT_DB))
							.build();
			
			final Config config_no_services = full_config.withoutPath("service");
			
			if (_mutable_state.service_manifest_override.isSet()) {
				if (!complete_services_set.equals(_mutable_state.service_manifest_override.get())) {
					throw new RuntimeException(ErrorUtils.SERVICE_RESTRICTIONS);
				}
			}
			else {
				_mutable_state.service_manifest_override.set(complete_services_set);
			}			
			
			// Ugh need to add: core deps, core + underlying management db to this list
			
			final Config service_subset = complete_services_set.stream() // DON'T MAKE PARALLEL SEE BELOW
				.map(clazz_name -> {
					final String config_path = clazz_name._2().orElse(clazz_name._1().getSimpleName().substring(1));
					return service_config.get().hasPath(config_path) 
							? Tuples._2T(config_path, service_config.get().getConfig(config_path)) 
							: null;
				})
				.filter(cfg -> null != cfg)
				.reduce(
						ConfigFactory.empty(),
						(acc, k_v) -> acc.withValue(k_v._1(), k_v._2().root()),
						(acc1, acc2) -> acc1 // (This will never be called as long as the above stream is not parallel)
						);
				
			final Config config_subset_services = config_no_services.withValue("service", service_subset.root());
			
			final Config last_call = 
					Lambdas.get(() -> 
						_mutable_state.module_config.isSet()
						?
						config_subset_services
							.withValue(__MY_MODULE_LIBRARY_ID, 
									ConfigValueFactory
										.fromAnyRef(BeanTemplateUtils.toJson(_mutable_state.module_config.get()).toString())
										)
						:
						config_subset_services						
					)
					.withValue(__MY_BUCKET_ID, 
								ConfigValueFactory
									.fromAnyRef(BeanTemplateUtils.toJson(bucket.orElseGet(() -> _mutable_state.bucket.get())).toString())
									)
					.withValue(__MY_TECH_LIBRARY_ID, 
								ConfigValueFactory
									.fromAnyRef(BeanTemplateUtils.toJson(_mutable_state.technology_config.get()).toString())
									)
									;
			
			return this.getClass().getName() + ":" + last_call.root().render(ConfigRenderOptions.concise());
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getGlobalHarvestTechnologyObjectStore()
	 */
	@Override
	public <S> ICrudService<S> getGlobalHarvestTechnologyObjectStore(final Class<S> clazz, final Optional<String> collection)
	{
		return this.getBucketObjectStore(clazz, Optional.empty(), collection, Optional.of(AssetStateDirectoryBean.StateDirectoryType.library));
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getGlobalModuleTechnologyObjectStore(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <S> Optional<ICrudService<S>> getGlobalModuleObjectStore(
			final Class<S> clazz, final Optional<String> collection) {
		return this.getModuleConfig().map(module_lib -> 
			_core_management_db.getPerLibraryState(clazz, module_lib, collection)
		);
	}	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getHarvestLibraries(java.util.Optional)
	 */
	@Override
	public CompletableFuture<Map<String, String>> getHarvestLibraries(
			final Optional<DataBucketBean> bucket) {
		if (_state_name == State.IN_TECHNOLOGY) {
			
			final DataBucketBean my_bucket = bucket.orElseGet(() -> _mutable_state.bucket.get());
			
			final SingleQueryComponent<SharedLibraryBean> tech_query = 
					CrudUtils.anyOf(SharedLibraryBean.class)
						.when(SharedLibraryBean::_id, my_bucket.harvest_technology_name_or_id())
						.when(SharedLibraryBean::path_name, my_bucket.harvest_technology_name_or_id());
			
			final List<SingleQueryComponent<SharedLibraryBean>> other_libs = 
				Optionals.ofNullable(my_bucket.harvest_configs()).stream()
					.flatMap(hcfg -> Optionals.ofNullable(hcfg.library_names_or_ids()).stream())
					.map(name -> {
						return CrudUtils.anyOf(SharedLibraryBean.class)
								.when(SharedLibraryBean::_id, name)
								.when(SharedLibraryBean::path_name, name);
					})
					.collect(Collector.of(
							LinkedList::new,
							LinkedList::add,
							(left, right) -> { left.addAll(right); return left; }
							));

			@SuppressWarnings("unchecked")
			final MultiQueryComponent<SharedLibraryBean> spec = CrudUtils.<SharedLibraryBean>anyOf(tech_query,
					other_libs.toArray(new SingleQueryComponent[other_libs.size()]));
			
			// Get the names or ids, get the shared libraries, get the cached ids (must be present)
			
			return this._core_management_db.readOnlyVersion().getSharedLibraryStore().getObjectsBySpec(spec, Arrays.asList("_id", "path_name"), true)
				.thenApply(cursor -> {
					return StreamSupport.stream(cursor.spliterator(), false)
						.collect(Collectors.<SharedLibraryBean, String, String>toMap(
								lib -> lib.path_name(), 
								lib -> _globals.local_cached_jar_dir() + "/" + lib._id() + ".cache.jar"));
				});
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getBucketObjectStore(java.lang.Class, java.util.Optional, java.util.Optional, boolean)
	 */
	@Override
	public <S> ICrudService<S> getBucketObjectStore(final Class<S> clazz, final Optional<DataBucketBean> bucket, final Optional<String> collection, final Optional<AssetStateDirectoryBean.StateDirectoryType> type)
	{		
		final Optional<DataBucketBean> this_bucket = 
				bucket
					.map(x -> Optional.of(x))
					.orElseGet(() -> _mutable_state.bucket.isSet() 
										? Optional.of(_mutable_state.bucket.get()) 
										: Optional.empty());
		
		return Patterns.match(type).<ICrudService<S>>andReturn()
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.analytic_thread == t.get(), 
						__ -> _core_management_db.getBucketAnalyticThreadState(clazz, this_bucket.get(), collection))
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.enrichment == t.get(), 
						__ -> _core_management_db.getBucketEnrichmentState(clazz, this_bucket.get(), collection))
				// assume this is the technology context, most likely usage
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.library == t.get(), 
						__ -> _core_management_db.getPerLibraryState(clazz, this.getTechnologyLibraryConfig(), collection))
				// default: harvest or not specified: harvest
				.otherwise(__ -> _core_management_db.getBucketHarvestState(clazz, this_bucket.get(), collection))
				;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getBucket()
	 */
	@Override
	public Optional<DataBucketBean> getBucket() {
		return _mutable_state.bucket.isSet() ? Optional.of(_mutable_state.bucket.get()) : Optional.empty();
	}
	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getBucketStatus(java.util.Optional)
	 */
	@Override
	public CompletableFuture<DataBucketStatusBean> getBucketStatus(
			final Optional<DataBucketBean> bucket) {
		return this._core_management_db
				.readOnlyVersion()
				.getDataBucketStatusStore()
				.getObjectById(bucket.orElseGet(() -> _mutable_state.bucket.get())._id())
				.thenApply(opt_status -> opt_status.get());		
		// (ie will exception if not present)
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#logStatusForBucketOwner(java.util.Optional, com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean, boolean)
	 */
	@Override
	public void logStatusForBucketOwner(
			Optional<DataBucketBean> bucket,
			BasicMessageBean message, boolean roll_up_duplicates) 
	{
		//TODO (ALEPH-19): Fill this in later
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#logStatusForBucketOwner(java.util.Optional, com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean)
	 */
	@Override
	public void logStatusForBucketOwner(
			Optional<DataBucketBean> bucket,
			BasicMessageBean message) {
		logStatusForBucketOwner(bucket, message, true);		
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getTempOutputLocation(java.util.Optional)
	 */
	@Override
	public String getTempOutputLocation(
			Optional<DataBucketBean> bucket) {
		return _globals.distributed_root_dir() + "/" + bucket.orElseGet(() -> _mutable_state.bucket.get()).full_name() + "/managed_bucket/import/temp/";
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getFinalOutputLocation(java.util.Optional)
	 */
	@Override
	public String getFinalOutputLocation(
			Optional<DataBucketBean> bucket) {
		return _globals.distributed_root_dir() + "/" + bucket.orElseGet(() -> _mutable_state.bucket.get()).full_name() + "/managed_bucket/import/ready/";
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#emergencyDisableBucket(java.util.Optional)
	 */
	@Override
	public void emergencyDisableBucket(Optional<DataBucketBean> bucket) {
		//TODO (ALEPH-19): Fill this in later (need distributed Akka working)
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#emergencyQuarantineBucket(java.util.Optional, java.lang.String)
	 */
	@Override
	public void emergencyQuarantineBucket(
			Optional<DataBucketBean> bucket,
			String quarantine_duration) {
		//TODO (ALEPH-19): Fill this in later (need distributed Akka working)
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getLibraryConfig()
	 */
	@Override
	public SharedLibraryBean getTechnologyLibraryConfig() {
		return _mutable_state.technology_config.get();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getModuleConfig()
	 */
	@Override
	public Optional<SharedLibraryBean> getModuleConfig() {
		return _mutable_state.module_config.isSet()
				? Optional.of(_mutable_state.module_config.get())
				: Optional.empty();
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		if (_state_name == State.IN_TECHNOLOGY) {
			if (!_mutable_state.service_manifest_override.isSet()) {
				throw new RuntimeException(ErrorUtils.SERVICE_RESTRICTIONS);				
			}
			return Stream.concat(
				Stream.of(this, _service_context)
				,
				_mutable_state.service_manifest_override.get().stream()
					.map(t2 -> _service_context.getService(t2._1(), t2._2()))
					.filter(service -> service.isPresent())
					.flatMap(service -> service.get().getUnderlyingArtefacts().stream())
			)
			.collect(Collectors.toList());
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(final Class<T> driver_class, final Optional<String> driver_options) {
		return Optional.empty();
	}

	@Override
	public void emitObject(Optional<DataBucketBean> bucket, Either<JsonNode, Map<String, Object>> object)
	{
		//TODO (ALEPH-41, ALEPH-12): Fill this in later (this dumps the JSON into the ready directory, right?)
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}
}
