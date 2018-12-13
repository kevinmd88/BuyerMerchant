package mod.wurmunlimited;

import com.wurmonline.math.TilePos;
import com.wurmonline.server.*;
import com.wurmonline.server.creatures.*;
import com.wurmonline.server.economy.Change;
import com.wurmonline.server.economy.Economy;
import com.wurmonline.server.economy.FakeShop;
import com.wurmonline.server.economy.MonetaryConstants;
import com.wurmonline.server.items.*;
import com.wurmonline.server.players.FakePlayerInfo;
import com.wurmonline.server.players.Player;
import mod.wurmunlimited.buyermerchant.BuyerMerchant;
import mod.wurmunlimited.buyermerchant.PriceList;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

public class WurmObjectsFactory {
    private Map<Long, Creature> creatures = new HashMap<>(10);
    private Map<Creature, FakeCommunicator> communicators = new HashMap<>(10);
    private Map<Creature, FakeShop> shops = new HashMap<>(4);
    private Map<Long, Item> items = new HashMap<>();
    private static int buyerContractId;
    private static WurmObjectsFactory current;

    static {
        try {
            ItemTemplateCreator.initialiseItemTemplates();
            new BuyerMerchant().onItemTemplatesCreated();
            buyerContractId = ItemTemplateFactory.getInstance().getTemplate("personal buyer contract").getTemplateId();

            Method createCreatureTemplate = CreatureTemplateCreator.class.getDeclaredMethod("createCreatureTemplate", int.class, String.class, String.class, String.class);
            createCreatureTemplate.setAccessible(true);

            createCreatureTemplate.invoke(null, 1, "Human", "Humans", "Another explorer.");
            createCreatureTemplate.invoke(null, 9, "Salesman", "Salesman", "An envoy from the king, buying and selling items.");
        } catch (IOException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public WurmObjectsFactory() throws Throwable {
        Server server = mock(Server.class);
        Field instance = Server.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, server);
        assert Server.getInstance() == server;
        when(server.getCreature(anyLong())).thenAnswer(i -> getCurrent().getCreature((long)i.getArgument(0)));

        Economy economy = mock(Economy.class);
        Field econ = Economy.class.getDeclaredField("economy");
        econ.setAccessible(true);
        econ.set(null, economy);
        assert Economy.getEconomy() == economy;
        when(economy.getShop(any(Creature.class))).thenAnswer(i -> shops.get((Creature)i.getArgument(0)));
        FakeShop shop = FakeShop.createFakeShop();
        shops.put(null, shop);
        when(economy.getKingsShop()).thenAnswer(i -> shops.get(null));
        doAnswer((Answer<Void>) i -> {
            Item coin = i.getArgument(0);

            coin.setTradeWindow(null);
            coin.setOwner(-10L, false);
            coin.setLastOwnerId(-10L);
            coin.setZoneId(-10, true);
            coin.setParentId(-10L, true);
            coin.setRarity((byte)0);
            coin.setBanked(true);
            return null;
        }).when(economy).returnCoin(any(Item.class), anyString());
        when(economy.getCoinsFor(anyLong())).thenAnswer((Answer<Item[]>) i -> {
            long total = i.getArgument(0);

            Set<Item> set = new HashSet<>();

            while (total >= MonetaryConstants.COIN_SILVER) {
                set.add(createNewSilverCoin());
                total -= MonetaryConstants.COIN_SILVER;
            }

            while (total >= 100) {
                set.add(createNewCopperCoin());
                total -= 100;
            }

            while (total > 0) {
                set.add(createNewIronCoin());
                total -= 1;
            }

            return set.toArray(new Item[0]);
        });
        when(economy.getChangeFor(anyLong())).thenAnswer(i -> new Change(i.getArgument(0)));

        current = this;
    }

    public void addCreature(Creature creature) {
        creatures.put(creature.getWurmId(), creature);
    }

    public static WurmObjectsFactory getCurrent() {
        return current;
    }

    public String[] getMessagesFor(Creature creature) {
        return communicators.get(creature).getMessages();
    }

    public void attachFakeCommunicator(Creature creature) {
        FakeCommunicator newCom = new FakeCommunicator(creature);
        creature.setCommunicator(newCom);
        communicators.put(creature, newCom);
    }

    public Creature createNewCreature() {
        try {
            Creature creature = Creature.doNew(CreatureTemplateIds.HUMAN_CID, 1, 1, 1, 1, "Creature" + (creatures.size() + 1), (byte)0);
            creatures.put(creature.getWurmId(), creature);
            creature.createPossessions();
            attachFakeCommunicator(creature);
            return creature;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Player createNewPlayer() {
        try {
            Player player = Player.doNewPlayer(CreatureTemplateIds.HUMAN_CID);
            player.setWurmId(WurmId.getNextPlayerId(), 1, 1, 1, 1);
            ServerPackageFactory.addPlayer(player);
            creatures.put(player.getWurmId(), player);
            FieldSetter.setField(player, Creature.class.getDeclaredField("status"), new FakeCreatureStatus(player));
            FieldSetter.setField(player, Player.class.getDeclaredField("saveFile"), new FakePlayerInfo(player.getName()));
            player.createPossessions();
            attachFakeCommunicator(player);
            return player;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Creature createNewBuyer(Creature owner) {
        Creature buyer;
        try {
            buyer = Creature.doNew(CreatureTemplateIds.SALESMAN_CID, 1, 1, 1, 1, "Buyer_" + (creatures.size() + 1), (byte)0);
            buyer.getSkills().learn(102, 1.0f);
            creatures.put(buyer.getWurmId(), buyer);
            buyer.createPossessions();
            attachFakeCommunicator(buyer);

            shops.put(buyer, FakeShop.createFakeShop(buyer, owner));
            buyer.getInventory().insertItem(createPriceList());
            return buyer;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Creature createNewTrader() {
        Creature trader;
        try {
            trader = Creature.doNew(CreatureTemplateIds.SALESMAN_CID, 1, 1, 1, 1, "Trader_" + (creatures.size() + 1), (byte)0);
            trader.getSkills().learn(102, 1.0f);
            creatures.put(trader.getWurmId(), trader);
            trader.createPossessions();
            attachFakeCommunicator(trader);

            shops.put(trader, FakeShop.createFakeTraderShop(trader.getWurmId()));
            return trader;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Item createPriceList() {
        return createPriceList("");
    }

    public Item createPriceList(String str) {
        Item newItem;
        try {
            newItem = PriceList.getNewPriceList();
            newItem.setInscription(str, "");
        } catch (FailedException | NoSuchTemplateException e) {
            throw new RuntimeException(e);
        }

        Items.putItem(newItem);
        return newItem;
    }

    public Item createBuyerContract() {
        return createNewItem(buyerContractId);
    }

    public Creature getCreature(long id) throws NoSuchCreatureException {
        if (!creatures.containsKey(id))
            throw new NoSuchCreatureException("");
        return creatures.get(id);
    }

    public Creature getCreature(String name) throws NoSuchCreatureException {
        Optional<Creature> creature = creatures.values().stream().filter(c -> c.getName().equals(name)).findAny();
        if (!creature.isPresent())
            throw new NoSuchCreatureException("");
        return creature.get();
    }

    public Collection<Creature> getAllCreatures() {
        return creatures.values();
    }

    public void removeCreature(Creature creature) {
        creatures.remove(creature.getWurmId());
    }

    public FakeShop getShop(Creature buyer) {
        return shops.get(buyer);
    }

    public FakeCommunicator getCommunicator(Creature creature) {
        return communicators.get(creature);
    }

    public Item createNewItem() {
        return createNewItem(ItemList.rake);
    }

    public Item createNewItem(int templateId) {
        Item item;
        try {
            item = ItemsPackageFactory.getTempItem(WurmId.getNextItemId());
            item.setTemplateId(templateId);
            item.setQualityLevel(1.0f);
            FieldSetter.setField(item, Item.class.getDeclaredField("weight"), ItemTemplateFactory.getInstance().getTemplate(templateId).getWeightGrams());
            item.setPosXYZRotation(1, 1, 1, 90);

            if (item.canHaveInscription()) {
                FieldSetter.setField(item, Item.class.getDeclaredField("inscription"), new InscriptionData(item.getWurmId(), "", "", 0));
            }

            items.put(item.getWurmId(), item);
            Items.putItem(item);
        } catch (NoSuchFieldException | NoSuchTemplateException e) {
            throw new RuntimeException(e);
        }

        return item;
    }

    public Item createNewSilverCoin() {
        return createNewItem(ItemList.coinSilver);
    }

    public Item createNewCopperCoin() {
        return createNewItem(ItemList.coinCopper);
    }

    public Item createNewIronCoin() {
        return createNewItem(ItemList.coinIron);
    }

    public Iterable<Item> createManyCopperCoins(int numberOfItems) {
        return () -> new Iterator<Item>() {
            int number = numberOfItems;

            @Override
            public boolean hasNext() {
                return number > 0;
            }

            @Override
            public Item next() {
                --number;
                return createNewCopperCoin();
            }
        };
    }

    public Item createNoTradeItem() {
        Item item = createNewItem(ItemList.wagonerTent);
        assert item.isNoTrade();
        return item;
    }

    public Iterable<Item> createManyItems(int numberOfItems) {
        return () -> new Iterator<Item>() {
            int number = numberOfItems;

            @Override
            public boolean hasNext() {
                return number > 0;
            }

            @Override
            public Item next() {
                --number;
                return createNewItem();
            }
        };
    }

    public Item createMarketStallAtCreature(Creature creature) {
        Item item = createNewItem();
        item.setTemplateId(ItemList.marketStall);
        TilePos pos = creature.getTilePos();
        item.setPosXY(pos.x, pos.y);
        return item;
    }

    public Item getItem(long id) {
        return items.get(id);
    }

    public void removeItem(long id) {
        if (!items.containsKey(id)) {
            System.out.println("Not in factory.items, PriceList item?");
            return;
        }
        System.out.println("Decaying " + items.get(id).getName());
        items.remove(id);
    }

    // Templates
    public int getIsHollowId() {
        return ItemList.backPack;
    }

    public int getIsMetalId() {
        return ItemList.hatchet;
    }

    public int getIsWoodId() {
        return ItemList.log;
    }

    public int getIsMeatId() {
        return ItemList.cookedMeat;
    }

    public int getIsCoinId() {
        return ItemList.coinCopper;
    }

    public int getIsLockableId() {
        return ItemList.chestSmall;
    }

    public int getIsUnknownMaterial() {
        return ItemList.dough;
    }

    public int getIsDefaultMaterialId() {
        return ItemList.rope;
    }

    public void lockItem(Item item) {
        assert item.isLockable();

        Item lock = createNewItem(ItemList.padLockSmall);
        assert lock.isLock();
        items.put(lock.getWurmId(), lock);

        item.setLockId(lock.getWurmId());
        lock.setLocked(true);
    }
}