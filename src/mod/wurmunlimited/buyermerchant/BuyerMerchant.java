package mod.wurmunlimited.buyermerchant;

import com.wurmonline.server.*;
import com.wurmonline.server.behaviours.*;
import com.wurmonline.server.creatures.*;
import com.wurmonline.server.economy.Economy;
import com.wurmonline.server.economy.Shop;
import com.wurmonline.server.items.*;
import com.wurmonline.server.questions.BuyerManagementQuestion;
import com.wurmonline.shared.constants.IconConstants;
import com.wurmonline.shared.constants.ItemMaterials;
import com.wurmonline.shared.util.StringUtilities;
import javassist.*;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.*;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuyerMerchant implements WurmServerMod, Configurable, PreInitable, Initable, ServerStartedListener, ItemTemplatesCreatedListener {
    private static final Logger logger = Logger.getLogger(BuyerMerchant.class.getName());
    private static final String BUYER_NAME_PREFIX = "Buyer_";
    private int templateId;
    private boolean updateTraders = false;
    // TODO - What about spells on items? - Probably going to ignore as Traders do, unless it is requested.
    // TODO - What about rarity - do later maybe?

    @Override
    public void onItemTemplatesCreated() {
        try {
            ItemTemplate template = new ItemTemplateBuilder("writ.buyer")
                    .name("personal buyer contract", "personal buyer contracts", "A contract declaring the rights for a person called a buyer to conduct trade on your behalf.")
                    .modelName("model.writ.merchant")
                    .imageNumber((short)IconConstants.ICON_TRADER_CONTRACT)
                    .weightGrams(0)
                    .dimensions(1, 10, 10)
                    .decayTime(Long.MAX_VALUE)
                    .material(ItemMaterials.MATERIAL_PAPER)
                    .behaviourType(BehaviourList.traderBookBehaviour)
                    .itemTypes(new short[] {
                            ItemTypes.ITEM_TYPE_INDESTRUCTIBLE,
                            ItemTypes.ITEM_TYPE_NODROP,
                            ItemTypes.ITEM_TYPE_HASDATA,
                            ItemTypes.ITEM_TYPE_FULLPRICE,
                            ItemTypes.ITEM_TYPE_LOADED,
                            ItemTypes.ITEM_TYPE_NOT_MISSION
                    })
                    .value(10000 * (Servers.localServer.isChallengeServer() ? 3 : 10))
                    .difficulty(100.0F)
                    .build();
            templateId = template.getTemplateId();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void configure(Properties properties) {
        String val = properties.getProperty("update_traders");
        if (val != null && val.equals("true"))
            updateTraders = true;
    }

    @Override
    public void init() {
        HookManager manager = HookManager.getInstance();

        // Placing Buyer with contract.
        manager.registerHook("com.wurmonline.server.behaviours.TraderBookBehaviour",
                "action",
                "(Lcom/wurmonline/server/behaviours/Action;Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/items/Item;SF)Z",
                () -> (o, method, args) -> TraderBookBehaviourAction(o, method, args, (short)args[3], (Item)args[2], (Creature)args[1]));
        manager.registerHook("com.wurmonline.server.behaviours.TraderBookBehaviour",
                "action",
                "(Lcom/wurmonline/server/behaviours/Action;Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/items/Item;Lcom/wurmonline/server/items/Item;SF)Z",
                () -> (o, method, args) -> TraderBookBehaviourAction(o, method, args, (short)args[4], (Item)args[3], (Creature)args[1]));

        // Create BuyerTrade instead of Trade if Buyer.
        manager.registerHook("com.wurmonline.server.behaviours.MethodsCreatures",
                "initiateTrade",
                "(Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/creatures/Creature;)V",
                () -> this::initiateTrade);

        // Insert BuyerHandler.
        manager.registerHook("com.wurmonline.server.creatures.Creature",
                "getTradeHandler",
                "()Lcom/wurmonline/server/creatures/TradeHandler;",
                () -> this::getTradeHandler);

        // Make Buyer Contract look like Merchant Contract for Trader selling and Player trading.
        // Restock sold Buyer Contracts due to templateId conflict.
        manager.registerHook("com.wurmonline.server.items.TradingWindow",
                "swapOwners",
                "()V",
                () -> this::swapOwners);

        // Add Buyer contract to traders default inventory.
        manager.registerHook("com.wurmonline.server.economy.Shop",
                "createShop",
                "(Lcom/wurmonline/server/creatures/Creature;)V",
                () -> this::createShop);

        // Differentiate between TradeManagementQuestion and Buyer version.
        // Hide price list when threatened.
        manager.registerHook("com.wurmonline.server.behaviours.CreatureBehaviour",
                "action",
                "(Lcom/wurmonline/server/behaviours/Action;Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/creatures/Creature;SF)Z",
                () -> this::action);

        // Adds coins "Give"-n to buyer to shop value.
        manager.registerHook("com.wurmonline.server.behaviours.CreatureBehaviour",
                "action",
                "(Lcom/wurmonline/server/behaviours/Action;Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/items/Item;Lcom/wurmonline/server/creatures/Creature;SF)Z",
                () -> this::giveAction);

        // Buyer examine message.
        manager.registerHook("com.wurmonline.server.behaviours.CreatureBehaviour",
                "handle_EXAMINE",
                "(Lcom/wurmonline/server/creatures/Creature;Lcom/wurmonline/server/creatures/Creature;)V",
                () -> this::handle_EXAMINE);

        // Catch call to TraderManagementQuestion in Creature auto-dismiss.
        manager.registerHook("com.wurmonline.server.questions.TraderManagementQuestion",
                "dismissMerchant",
                "(Lcom/wurmonline/server/creatures/Creature;J)V",
                () -> this::dismissMerchant);

        // Remove no decay from price list on death.
        manager.registerHook("com.wurmonline.server.creatures.Creature",
                "die",
                "(ZLjava/lang/String;)V",
                () -> this::die);

        // StopLoggers.
        manager.registerHook("com.wurmonline.server.items.TradingWindow",
                "stopLoggers",
                "()V",
                () -> this::stopLoggers);

        // TODO - Is this the best way?  Anyway to get other mods from ModLoader?
        // Compatibility fix with Increase Merchant Max Items mod.
        try {
            InputStream file = Files.newInputStream(Paths.get("./mods/increasemerchantitems.properties"));
            Properties properties = new Properties();
            properties.load(file);

            Class<?> BuyerHandler = Class.forName("com.wurmonline.server.creatures.BuyerHandler");
            BuyerHandler.getDeclaredField("maxPersonalItems").set(null, Integer.parseInt(properties.getProperty("MaxItems")));
            logger.info("increasemerchantsitems value loaded.");
        } catch (IOException ignored) {
        } catch (IllegalAccessException | ClassNotFoundException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void preInit() {
        ClassPool pool = HookManager.getInstance().getClassPool();
        try {
            // Remove final from TradeHandler and TradingWindow.
            CtClass tradeHandler = pool.get("com.wurmonline.server.creatures.TradeHandler");
            tradeHandler.defrost();
            tradeHandler.setModifiers(Modifier.clear(tradeHandler.getModifiers(), Modifier.FINAL));
            // Add empty constructor.
            tradeHandler.addConstructor(CtNewConstructor.make(tradeHandler.getSimpleName() + "(){}", tradeHandler));

            CtClass tradingWindow = pool.get("com.wurmonline.server.items.TradingWindow");
            tradingWindow.defrost();
            tradingWindow.setModifiers(Modifier.clear(tradingWindow.getModifiers(), Modifier.FINAL));

            // Add empty constructor.
            CtClass trade = pool.get("com.wurmonline.server.items.Trade");
            trade.defrost();
            trade.addConstructor(CtNewConstructor.make(trade.getSimpleName() + "(){}", trade));
            // Remove final from public fields.
            CtField creatureOne = trade.getDeclaredField("creatureOne");
            creatureOne.setModifiers(Modifier.clear(creatureOne.getModifiers(), Modifier.FINAL));
            CtField creatureTwo = trade.getDeclaredField("creatureTwo");
            creatureTwo.setModifiers(Modifier.clear(creatureTwo.getModifiers(), Modifier.FINAL));

            // Remove final from TradingWindow.stopLoggers.
            CtMethod stopLoggers = tradingWindow.getDeclaredMethod("stopLoggers");
            stopLoggers.setModifiers(Modifier.clear(stopLoggers.getModifiers(), Modifier.FINAL));

            // Then load subclasses.
            // TODO - Why do I need to load PriceList?  Because it needs to be on the same loader as the rest of the Buyer code?
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("PriceList.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("PriceList$Entry.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("PriceList$NoPriceListOnBuyer.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("PriceList$PriceListFullException.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("BuyerHandler.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("BuyerTradingWindow.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("BuyerTrade.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("QuestionExtension.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("BuyerManagementQuestion.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("SetBuyerPricesQuestion.class"));
            pool.makeClass(BuyerMerchant.class.getResourceAsStream("AddItemToBuyerQuestion.class"));
        } catch (NotFoundException | IOException | CannotCompileException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onServerStarted() {
        if (updateTraders) {
            for (Shop shop : Economy.getTraders()) {
                Creature creature = Creatures.getInstance().getCreatureOrNull(shop.getWurmId());
                if (!shop.isPersonal() && creature != null && creature.getInventory().getItems().stream().noneMatch(i -> i.getTemplateId() == templateId)) {
                    try {
                        creature.getInventory().insertItem(Creature.createItem(templateId, (float) (10 + Server.rand.nextInt(80))));
                        shop.setMerchantData(shop.getNumberOfItems() + 1);
                    } catch (Exception e) {
                        logger.log(Level.INFO, "Failed to create merchant inventory items for shop, creature: " + creature.getName(), e);
                    }
                }
            }
        }
    }

    static boolean isBuyer(Creature creature) {
        return creature.getName().startsWith(BUYER_NAME_PREFIX) && creature.getTemplate().id == CreatureTemplateIds.SALESMAN_CID;
    }

    Object TraderBookBehaviourAction(Object o, Method method, Object[] args, short action, Item target, Creature performer)
            throws InvocationTargetException, IllegalAccessException {

        if (action == 85) {
            if (target.getTemplateId() == templateId) {
                if (target.getData() == -1L && !Methods.isActionAllowed(performer, action)) {
                    return true;
                }

                BuyerManagementQuestion tq = new BuyerManagementQuestion(performer, target.getWurmId());
                tq.sendQuestion();
                return true;
            }
        }

        return method.invoke(o, args);
    }

    Object initiateTrade(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException {
        Creature performer = (Creature) args[0];
        Creature opponent = (Creature) args[1];

        if (isBuyer(opponent)) {
            Class<?> BuyerTradeClass = Class.forName("com.wurmonline.server.items.BuyerTrade");
            Object trade;
            try {
                trade = BuyerTradeClass.getConstructor(Creature.class, Creature.class).newInstance(performer, opponent);
            } catch (InvocationTargetException e) {
                logger.warning(e.getCause().getMessage());
                performer.getCommunicator().sendNormalServerMessage(opponent.getName() + " has misplaced their price list and cannot trade.");
                return null;
            }

            performer.setTrade((Trade) trade);
            opponent.setTrade((Trade) trade);
            opponent.getCommunicator().sendStartTrading(performer);
            performer.getCommunicator().sendStartTrading(opponent);
            opponent.addItemsToTrade();
            return null;
        } else {
            return method.invoke(o, args);
        }
    }

    Object getTradeHandler(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException, NoSuchFieldException {
        Creature creature = (Creature) o;
        if (!isBuyer(creature))
            return method.invoke(o, args);
        Field tradeHandler = Creature.class.getDeclaredField("tradeHandler");
        tradeHandler.setAccessible(true);
        TradeHandler handler = (TradeHandler) tradeHandler.get(creature);

        if (handler == null) {
            Class<?> BuyerHandler = Class.forName("com.wurmonline.server.creatures.BuyerHandler");
            handler = (TradeHandler) BuyerHandler.getConstructor(Creature.class, Trade.class).newInstance(creature, creature.getTrade());
            tradeHandler.set(creature, handler);
        }

        return handler;
    }

    Object swapOwners(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        List<Item> contracts = Stream.of(((TradingWindow) o).getItems()).filter(item -> item.getTemplateId() == templateId).collect(Collectors.toList());
        contracts.forEach(item -> item.setTemplateId(ItemList.merchantContract));

        try {
            method.invoke(o, args);

            if (contracts.size() > 0) {
                Field windowOwner = TradingWindow.class.getDeclaredField("windowowner");
                windowOwner.setAccessible(true);
                Creature trader = (Creature)windowOwner.get(o);
                if (trader.isNpcTrader() && !trader.getShop().isPersonal()) {
                    Item contract = contracts.get(0);
                    Item newItem = ItemFactory.createItem(templateId, contract.getQualityLevel(), contract.getTemplate().getMaterial(), (byte)0, null);
                    trader.getInventory().insertItem(newItem);
                }
            }
        } catch (NoSuchTemplateException | FailedException | NoSuchFieldException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        } finally {
            contracts.forEach(item -> item.setTemplateId(templateId));
        }
        return null;
    }

    Object createShop(Object o, Method method, Object[] args) throws Exception { // Plus InvocationTargetException, IllegalAccessException
        Creature toReturn = (Creature) args[0];

        Item inventory = toReturn.getInventory();
        inventory.insertItem(Creature.createItem(templateId, (float) (10 + Server.rand.nextInt(80))));

        return method.invoke(o, args);
    }

    Object action(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Creature target = (Creature) args[2];

        if (isBuyer(target)) {
            Action act = (Action) args[0];
            Creature performer = (Creature) args[1];
            short action = (short) args[3];


            if (action == Actions.MANAGE) {
                Method mayDismiss = CreatureBehaviour.class.getDeclaredMethod("mayDismissMerchant", Creature.class, Creature.class);
                mayDismiss.setAccessible(true);
                if (target.isNpcTrader() && (boolean) mayDismiss.invoke(o, performer, target)) {
                    // TODO - Why does this work but BuyerTrade and BuyerHandler don't?
                    BuyerManagementQuestion tmq = new BuyerManagementQuestion(performer, target);
                    tmq.sendQuestion();
                    return true;
                }
            } else if (action == Actions.THREATEN) {
                float counter = (float) args[4];
                if (counter * 10.0F > (float) act.getTimeLeft()) {
                    // TODO - Would this cause issues if the server goes down during a swap?
                    Item priceList = null;
                    for (Item item : target.getInventory().getItems()) {
                        if (PriceList.isPriceList(item)) {
                            priceList = item;
                            Method removeItem = Item.class.getDeclaredMethod("removeItem", Item.class);
                            removeItem.setAccessible(true);
                            removeItem.invoke(target.getInventory(), item);
                            break;
                        }
                    }
                    boolean toReturn = (boolean) method.invoke(o, args);

                    if (priceList != null)
                        target.getInventory().insertItem(priceList);

                    return toReturn;
                }
            }
        }
        return method.invoke(o, args);
    }

    Object giveAction(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        Creature performer = (Creature) args[1];
        Creature target = (Creature) args[3];
        Item item = (Item)args[2];
        short action = (short)args[4];

        if (action == Actions.GIVE && isBuyer(target) && item.isCoin()) {

                boolean toReturn = (boolean)method.invoke(o, args);

                if (target.getInventory().getItems().contains(item)) {
                    Shop shop = target.getShop();
                    int value = Economy.getValueFor(item.getTemplateId());
                    logger.info(target.getName() + " - " + performer.getName() + " wants to Give me " + value + ".  Current shop value - " + shop.getMoney());
                    shop.setMoney(shop.getMoney() + value);
                    logger.info(target.getName() + " - My shop is now at " + shop.getMoney());
                }

                return toReturn;
        }
        return method.invoke(o, args);
    }

    Object handle_EXAMINE(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        Creature performer = (Creature) args[0];
        Creature target = (Creature) args[1];

        // TODO - Should I also show skills when GM?  Does that really affect anything?
        if (isBuyer(target)) {
            String message = target.getName() + " is here buying items.";
            Shop shop = Economy.getEconomy().getShop(target);
            try {
                message = target.getName() + " is here buying items on behalf of " + Players.getInstance().getNameFor(shop.getOwnerId()) + ".";
            } catch (NoSuchPlayerException | IOException var16) {
                logger.log(Level.WARNING, var16.getMessage(), var16);
            }
            performer.getCommunicator().sendNormalServerMessage(message);
            performer.getCommunicator().sendNormalServerMessage(StringUtilities.raiseFirstLetter(target.getStatus().getBodyType()));
            return null;
        }
        return method.invoke(o, args);
    }

    Object dismissMerchant(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException, NoSuchCreatureException {
        Creature trader = Creatures.getInstance().getCreature((long) args[1]);
        if (isBuyer(trader)) {
            BuyerManagementQuestion.dismissMerchant((Creature) args[0], (long) args[1]);
            return null;
        }
        return method.invoke(o, args);
    }

    Object die(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        Creature buyer = (Creature) o;
        if (isBuyer(buyer)) {
            for (Item item : buyer.getInventory().getItems()) {
                if (PriceList.isPriceList(item)) {
                    item.setHasNoDecay(false);
                    break;
                }
            }
        }
        return method.invoke(o, args);
    }

    Object stopLoggers(Object o, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException {
        Class<?> BuyerTradingWindow = Class.forName("com.wurmonline.server.items.BuyerTradingWindow");
        BuyerTradingWindow.getMethod("stopLoggers").invoke(null);
        return method.invoke(o, args);
    }
}