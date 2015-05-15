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
package com.ikanow.aleph2.data_import_manager.module;


import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import com.ikanow.aleph2.data_import_manager.services.DataImportManager;
import com.ikanow.aleph2.data_model.module.DefaultModule;

public class DataImportManagerModule extends DefaultModule implements Module{

	public void configure(Binder binder) {
		Names.bindProperties(binder, getProperties());
		binder.bind(DataImportManager.class).in(Scopes.SINGLETON);

	}


}
