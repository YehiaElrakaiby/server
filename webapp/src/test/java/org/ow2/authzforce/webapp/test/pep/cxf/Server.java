/**
 * Copyright (C) 2012-2018 Thales Services SAS.
 *
 * This file is part of AuthzForce CE.
 *
 * AuthzForce CE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AuthzForce CE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AuthzForce CE.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.ow2.authzforce.webapp.test.pep.cxf;

import java.net.URL;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.spring.SpringBusFactory;
import org.apache.cxf.testutil.common.AbstractBusTestServerBase;

public class Server extends AbstractBusTestServerBase
{

	public Server()
	{

	}

	@Override
	protected void run()
	{
		final URL busFile = Server.class.getResource("cxf-doubleit-service.xml");
		final Bus busLocal = new SpringBusFactory().createBus(busFile);
		BusFactory.setDefaultBus(busLocal);
		setBus(busLocal);

		try
		{
			new Server();
		}
		catch (final Exception e)
		{
			e.printStackTrace();
		}
	}
}
